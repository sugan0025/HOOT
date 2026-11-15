package app.hoot.ui.wheel

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Analytical ring-sector wedge geometry with circular fillet corner tangency,
 * constant linear gaps, and adaptive bisection clamping, porting Windows Hoot DonutGeometry.cs.
 *
 * Angles are measured clockwise from 12 o'clock, in radians.
 * Slice i is centred on i * span, so slice 0 sits at the top (12 o'clock)
 * and the rest follow clockwise.
 */
object DonutWedgeGeometry {

    const val DEFAULT_GAP_PX: Float = 3.0f
    const val MIN_CORNER_RADIUS: Float = 0.25f
    const val SUB_OVERHANG: Float = 0.25f
    const val MIN_SUB_WIDTH: Float = 44f

    data class Point(val x: Float, val y: Float) {
        operator fun plus(other: Point) = Point(x + other.x, y + other.y)
        operator fun minus(other: Point) = Point(x - other.x, y - other.y)
        operator fun times(factor: Float) = Point(x * factor, y * factor)
        operator fun div(factor: Float) = Point(x / factor, y / factor)
        fun length(): Float = sqrt(x * x + y * y)
        fun dot(other: Point): Float = x * other.x + y * other.y
    }

    data class CornerData(
        val center: Point,
        val onArc: Point,
        val onLine: Point,
        val along: Float
    )

    data class RoundedWedge(
        val oStart: CornerData,
        val iStart: CornerData,
        val oEnd: CornerData,
        val iEnd: CornerData,
        val radius: Float
    )

    /**
     * Converts a clockwise-from-top angle and radius into a point relative to center.
     * Matches Windows Hoot DonutGeometry.PointAt(center, radius, angle).
     */
    fun pointAt(center: Offset, radius: Float, angleRad: Float): Point =
        Point(
            center.x + radius * sin(angleRad),
            center.y - radius * cos(angleRad)
        )

    /**
     * Clockwise-from-top angle (0..2π) of p around center.
     * Matches Windows Hoot DonutGeometry.AngleOf(center, p).
     */
    fun angleOf(center: Offset, p: Point): Float {
        val a = atan2(p.x - center.x, center.y - p.y)
        return if (a < 0f) a + (2f * PI).toFloat() else a
    }

    /**
     * Signed angle offset of p from midRad in [-π, π].
     */
    fun angleOffset(center: Offset, p: Point, midRad: Float): Float {
        var diff = (angleOf(center, p) - midRad) % (2f * PI.toFloat())
        if (diff > PI.toFloat()) diff -= (2f * PI).toFloat()
        if (diff < -PI.toFloat()) diff += (2f * PI).toFloat()
        return diff
    }

    fun distance(a: Point, b: Point): Float = (a - b).length()

    /**
     * Solves quadratic tangency between circular arc and straight radial edge for a corner fillet.
     * Matches Windows Hoot DonutGeometry.Corner.
     */
    fun solveCorner(
        center: Offset,
        outerSide: Point,
        innerSide: Point,
        circleRadius: Float,
        radius: Float,
        isOuter: Boolean
    ): CornerData? {
        val len = distance(outerSide, innerSide)
        if (len <= 0.001f) return null

        val ux = (innerSide.x - outerSide.x) / len
        val uy = (innerSide.y - outerSide.y) / len
        val u = Point(ux, uy)

        val outerAngle = angleOf(center, outerSide)
        // Tangent in clockwise direction: derivative of (sin(a), -cos(a)) is (cos(a), sin(a))
        val tangent = Point(cos(outerAngle), sin(outerAngle))

        // Normal pointing into the slice (clockwise side for start edge)
        var nx = -uy
        var ny = ux
        if (nx * tangent.x + ny * tangent.y < 0f) {
            nx = -nx
            ny = -ny
        }
        val n = Point(nx, ny)

        val dist = if (isOuter) circleRadius - radius else circleRadius + radius
        val w = outerSide + (n * radius) - Point(center.x, center.y)

        val b = w.dot(u)
        val disc = b * b - (w.dot(w) - dist * dist)
        if (disc < 0f) return null

        val t = -b - sqrt(disc)
        if (t < 0f || t > len) return null

        val c = outerSide + (n * radius) + (u * t)
        val toCenter = c - Point(center.x, center.y)
        val toCenterLen = toCenter.length()
        if (toCenterLen <= 0.0001f) return null

        val onArc = Point(center.x, center.y) + (toCenter / toCenterLen) * circleRadius
        val onLine = outerSide + (u * t)

        return CornerData(center = c, onArc = onArc, onLine = onLine, along = t)
    }

    fun buildRounded(
        center: Offset,
        rInner: Float,
        rOuter: Float,
        midRad: Float,
        outerHalf: Float,
        innerHalf: Float,
        radius: Float
    ): RoundedWedge? {
        val outerSide = pointAt(center, rOuter, midRad - outerHalf)
        val innerSide = pointAt(center, rInner, midRad - innerHalf)

        val o = solveCorner(center, outerSide, innerSide, rOuter, radius, isOuter = true) ?: return null
        val i = solveCorner(center, outerSide, innerSide, rInner, radius, isOuter = false) ?: return null

        // Overlap and symmetry guard checks
        if (o.along >= i.along ||
            angleOffset(center, o.center, midRad) >= 0f ||
            angleOffset(center, i.center, midRad) >= 0f
        ) {
            return null
        }

        fun mirror(p: Point): Point {
            val dist = distance(Point(center.x, center.y), p)
            val angle = 2f * midRad - angleOf(center, p)
            return pointAt(center, dist, angle)
        }

        val oEnd = CornerData(mirror(o.center), mirror(o.onArc), mirror(o.onLine), o.along)
        val iEnd = CornerData(mirror(i.center), mirror(i.onArc), mirror(i.onLine), i.along)

        return RoundedWedge(oStart = o, iStart = i, oEnd = oEnd, iEnd = iEnd, radius = radius)
    }

    private fun arcBetween(
        path: Path,
        c: Point,
        from: Point,
        to: Point,
        radius: Float
    ) {
        val rect = Rect(c.x - radius, c.y - radius, c.x + radius, c.y + radius)
        val a1 = atan2(from.y - c.y, from.x - c.x) * (180f / PI.toFloat())
        val a2 = atan2(to.y - c.y, to.x - c.x) * (180f / PI.toFloat())
        var sw = a2 - a1
        while (sw < 0f) sw += 360f
        while (sw >= 360f) sw -= 360f
        path.arcTo(rect, a1, sw, forceMoveTo = false)
    }

    /**
     * Builds an authentic concentric arc ring-sector wedge Path with constant linear gap
     * and analytical circular corner fillets, exactly matching Windows Hoot DonutGeometry.Wedge.
     */
    fun createWedgePath(
        center: Offset,
        innerRadius: Float,
        outerRadius: Float,
        midAngleRad: Float,
        spanRad: Float,
        gapPx: Float = DEFAULT_GAP_PX,
        cornerRadiusPx: Float = 10f
    ): Path {
        val r1 = innerRadius.coerceAtLeast(1f)
        val r2 = outerRadius.coerceAtLeast(r1 + 2f)

        // Single slice / full ring
        if (spanRad >= (2f * PI).toFloat() - 0.001f) {
            return Path().apply {
                fillType = PathFillType.EvenOdd
                addOval(Rect(center.x - r2, center.y - r2, center.x + r2, center.y + r2))
                addOval(Rect(center.x - r1, center.y - r1, center.x + r1, center.y + r1))
            }
        }

        // Constant linear perpendicular gap: angular half-offset = (gap/2) / radius
        val outerHalf = (spanRad / 2f - (gapPx / 2f) / r2).coerceAtLeast(0.001f)
        val innerHalf = (spanRad / 2f - (gapPx / 2f) / r1).coerceAtLeast(0.001f)

        val maxCornerRadius = (r2 - r1) / 2f
        val targetRadius = cornerRadiusPx.coerceIn(0f, maxCornerRadius)

        var rounded: RoundedWedge? = null
        if (targetRadius > MIN_CORNER_RADIUS) {
            rounded = buildRounded(center, r1, r2, midAngleRad, outerHalf, innerHalf, targetRadius)
            if (rounded == null) {
                // Adaptive 14-step bisection search for largest fitting corner radius
                var low = 0f
                var high = targetRadius
                for (step in 0 until 14) {
                    val r = (low + high) / 2f
                    val test = buildRounded(center, r1, r2, midAngleRad, outerHalf, innerHalf, r)
                    if (test != null) {
                        rounded = test
                        low = r
                    } else {
                        high = r
                    }
                }
                if (low <= MIN_CORNER_RADIUS) rounded = null
            }
        }

        val path = Path()
        val outerRect = Rect(center.x - r2, center.y - r2, center.x + r2, center.y + r2)
        val innerRect = Rect(center.x - r1, center.y - r1, center.x + r1, center.y + r1)
        // Convert clockwise-from-12-o'clock angle to Android canvas degrees (0° at 3 o'clock)
        val midDeg = midAngleRad * (180f / PI.toFloat()) - 90f

        if (rounded != null) {
            val r = rounded.radius
            val oStart = rounded.oStart
            val oEnd = rounded.oEnd
            val iEnd = rounded.iEnd
            val iStart = rounded.iStart

            val alphaOuter = abs(angleOffset(center, oEnd.onArc, midAngleRad)) * (180f / PI.toFloat())
            val alphaInner = abs(angleOffset(center, iEnd.onArc, midAngleRad)) * (180f / PI.toFloat())

            // 1. Outer start point
            path.moveTo(oStart.onArc.x, oStart.onArc.y)

            // 2. Concentric outer arc (clockwise)
            path.arcTo(outerRect, midDeg - alphaOuter, 2f * alphaOuter, forceMoveTo = false)

            // 3. Trailing outer corner fillet (clockwise)
            arcBetween(path, oEnd.center, oEnd.onArc, oEnd.onLine, r)

            // 4. Trailing straight radial edge
            path.lineTo(iEnd.onLine.x, iEnd.onLine.y)

            // 5. Trailing inner corner fillet (clockwise)
            arcBetween(path, iEnd.center, iEnd.onLine, iEnd.onArc, r)

            // 6. Concentric inner arc (counter-clockwise)
            path.arcTo(innerRect, midDeg + alphaInner, -2f * alphaInner, forceMoveTo = false)

            // 7. Leading inner corner fillet (clockwise)
            arcBetween(path, iStart.center, iStart.onArc, iStart.onLine, r)

            // 8. Leading straight radial edge
            path.lineTo(oStart.onLine.x, oStart.onLine.y)

            // 9. Leading outer corner fillet (clockwise)
            arcBetween(path, oStart.center, oStart.onLine, oStart.onArc, r)

            path.close()
        } else {
            // Sharp fallback wedge
            val outerStart = pointAt(center, r2, midAngleRad - outerHalf)
            val innerEnd = pointAt(center, r1, midAngleRad + innerHalf)

            val outerHalfDeg = outerHalf * (180f / PI.toFloat())
            val innerHalfDeg = innerHalf * (180f / PI.toFloat())

            path.moveTo(outerStart.x, outerStart.y)
            path.arcTo(outerRect, midDeg - outerHalfDeg, 2f * outerHalfDeg, forceMoveTo = false)
            path.lineTo(innerEnd.x, innerEnd.y)
            path.arcTo(innerRect, midDeg + innerHalfDeg, -2f * innerHalfDeg, forceMoveTo = false)
            path.close()
        }
        return path
    }

    /**
     * Exact Windows Hoot Group Chevron Mark pointing outward toward the outer edge.
     */
    fun createGroupMarkPath(
        center: Offset,
        outerRadius: Float,
        midAngleRad: Float,
        scale: Float = 1.0f
    ): Path {
        val inset = 8f * scale
        val depth = 5f * scale
        val halfWidth = 5.5f * scale

        val tipRadius = outerRadius - inset
        val backRadius = tipRadius - depth
        val turn = halfWidth / backRadius

        val tip = pointAt(center, tipRadius, midAngleRad)
        val leftArm = pointAt(center, backRadius, midAngleRad - turn)
        val rightArm = pointAt(center, backRadius, midAngleRad + turn)

        return Path().apply {
            moveTo(leftArm.x, leftArm.y)
            lineTo(tip.x, tip.y)
            lineTo(rightArm.x, rightArm.y)
        }
    }

    /**
     * Width of each slice of a ring of count slices, matching Windows Hoot DonutGeometry.SubSpan.
     */
    fun calculateSubSpanRad(parentSpanRad: Float, count: Int, midRadius: Float = 0f): Float {
        if (count <= 0) return 0f
        var span = parentSpanRad * (1f + 2f * SUB_OVERHANG) / count
        if (midRadius > 0f) {
            span = maxOf(span, MIN_SUB_WIDTH / midRadius)
        }
        return minOf(span, (2f * PI.toFloat()) / count)
    }
}
