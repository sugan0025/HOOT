package app.hoot.ui.wheel

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class DonutWedgeGeometryTest {

    @Test
    fun testConstantLinearGapMath() {
        val r1 = 66f
        val r2 = 150f
        val gap = 3.0f
        val span = (PI / 2).toFloat() // 90 degrees

        val outerHalf = span / 2f - (gap / 2f) / r2
        val innerHalf = span / 2f - (gap / 2f) / r1

        // Linear perpendicular offset: r * sin((gap / 2) / r) ~ gap / 2 = 1.5
        val linearOffsetOuter = r2 * sin((gap / 2f) / r2)
        val linearOffsetInner = r1 * sin((gap / 2f) / r1)

        assertEquals(gap / 2f, linearOffsetOuter, 0.05f)
        assertEquals(gap / 2f, linearOffsetInner, 0.05f)
    }

    @Test
    fun testSolveCornerTangency() {
        val center = Offset(0f, 0f)
        val rInner = 60f
        val rOuter = 150f
        val midAngle = 0f // 12 o'clock
        val span = (PI / 3).toFloat() // 60 degrees
        val gap = 3.0f
        val cornerRadius = 10f

        val outerHalf = span / 2f - (gap / 2f) / rOuter
        val innerHalf = span / 2f - (gap / 2f) / rInner

        val outerSide = DonutWedgeGeometry.pointAt(center, rOuter, midAngle - outerHalf)
        val innerSide = DonutWedgeGeometry.pointAt(center, rInner, midAngle - innerHalf)

        // Outer corner
        val outerCorner = DonutWedgeGeometry.solveCorner(
            center = center,
            outerSide = outerSide,
            innerSide = innerSide,
            circleRadius = rOuter,
            radius = cornerRadius,
            isOuter = true
        )
        assertNotNull(outerCorner)
        val o = outerCorner!!

        // Distance from center to outer fillet center must be exactly rOuter - cornerRadius
        val distOToCenter = sqrt(o.center.x * o.center.x + o.center.y * o.center.y)
        assertEquals(rOuter - cornerRadius, distOToCenter, 0.05f)

        // Distance from center to onArc must be exactly rOuter
        val distOnArcO = sqrt(o.onArc.x * o.onArc.x + o.onArc.y * o.onArc.y)
        assertEquals(rOuter, distOnArcO, 0.05f)

        // Distance from fillet center to onArc must be cornerRadius
        val distCToOnArcO = (o.center - o.onArc).length()
        assertEquals(cornerRadius, distCToOnArcO, 0.05f)

        // Inner corner
        val innerCorner = DonutWedgeGeometry.solveCorner(
            center = center,
            outerSide = outerSide,
            innerSide = innerSide,
            circleRadius = rInner,
            radius = cornerRadius,
            isOuter = false
        )
        assertNotNull(innerCorner)
        val i = innerCorner!!

        // Distance from center to inner fillet center must be exactly rInner + cornerRadius
        val distIToCenter = sqrt(i.center.x * i.center.x + i.center.y * i.center.y)
        assertEquals(rInner + cornerRadius, distIToCenter, 0.05f)

        // Distance from center to onArc must be exactly rInner
        val distOnArcI = sqrt(i.onArc.x * i.onArc.x + i.onArc.y * i.onArc.y)
        assertEquals(rInner, distOnArcI, 0.05f)

        // Outer corner along parameter must be smaller than inner corner (no overlap along radial edge)
        assertTrue(o.along < i.along)
    }

    @Test
    fun testBuildRoundedSymmetry() {
        val center = Offset(100f, 100f)
        val rInner = 60f
        val rOuter = 150f
        val midAngle = (PI / 4).toFloat()
        val span = (PI / 3).toFloat()
        val gap = 3.0f
        val cornerRadius = 10f

        val outerHalf = span / 2f - (gap / 2f) / rOuter
        val innerHalf = span / 2f - (gap / 2f) / rInner

        val rounded = DonutWedgeGeometry.buildRounded(
            center = center,
            rInner = rInner,
            rOuter = rOuter,
            midRad = midAngle,
            outerHalf = outerHalf,
            innerHalf = innerHalf,
            radius = cornerRadius
        )
        assertNotNull(rounded)
        val r = rounded!!

        // Mirrored points must have equal radial distances from center
        val distOStart = (r.oStart.center - DonutWedgeGeometry.Point(center.x, center.y)).length()
        val distOEnd = (r.oEnd.center - DonutWedgeGeometry.Point(center.x, center.y)).length()
        assertEquals(distOStart, distOEnd, 0.05f)

        val distIStart = (r.iStart.center - DonutWedgeGeometry.Point(center.x, center.y)).length()
        val distIEnd = (r.iEnd.center - DonutWedgeGeometry.Point(center.x, center.y)).length()
        assertEquals(distIStart, distIEnd, 0.05f)

        // Trailing and leading angle offsets from mid must be symmetric
        val offsetStart = DonutWedgeGeometry.angleOffset(center, r.oStart.center, midAngle)
        val offsetEnd = DonutWedgeGeometry.angleOffset(center, r.oEnd.center, midAngle)
        assertEquals(-offsetStart, offsetEnd, 0.001f)
    }

    @Test
    fun testCalculateSubSpanDeg() {
        val span6 = DonutWedgeGeometry.calculateSubSpanRad(
            parentSpanRad = (2f * PI / 6f).toFloat(),
            count = 3
        ) * (180f / PI.toFloat())
        // sliceSpan = 60°, span = 60 * 1.5 / 3 = 30°
        assertEquals(30f, span6, 0.1f)

        val span4 = DonutWedgeGeometry.calculateSubSpanRad(
            parentSpanRad = (2f * PI / 4f).toFloat(),
            count = 2
        ) * (180f / PI.toFloat())
        // sliceSpan = 90°, span = 90 * 1.5 / 2 = 67.5°
        assertEquals(67.5f, span4, 0.1f)
    }

    @Test
    fun testHitTestSlice12ClockAlignment() {
        val center = Offset(300f, 300f)
        val outerRadius = 150f
        val count = 8

        // Slice 0 is centered at 12 o'clock (0 rad, directly above center)
        val touch0 = Offset(300f, 200f)
        assertEquals(0, hitTestSlice(touch0, center, outerRadius, count))

        // Slice 1 is at 1:30 (45° clockwise)
        val touch1 = Offset(300f + 70f, 300f - 70f)
        assertEquals(1, hitTestSlice(touch1, center, outerRadius, count))

        // Slice 2 is at 3:00 (90° clockwise)
        val touch2 = Offset(400f, 300f)
        assertEquals(2, hitTestSlice(touch2, center, outerRadius, count))

        // Slice 4 is at 6:00 (180° clockwise, directly below center)
        val touch4 = Offset(300f, 400f)
        assertEquals(4, hitTestSlice(touch4, center, outerRadius, count))
    }

    @Test
    fun testHitTestSubSlice() {
        val center = Offset(300f, 300f)
        val outerRadius = 150f
        val expandedGroupIndex = 0
        val sliceCount = 6
        val subCount = 3

        // Group 0 parent mid: 0 (12 o'clock)
        // Sub 1 is at the center of the fan (deltaDeg = 0)
        // Point is at 12 o'clock (directly above center)
        val testR = 180f
        val touchPos = Offset(center.x, center.y - testR)

        val hit = hitTestSubSlice(
            touchPos = touchPos,
            center = center,
            outerRadius = outerRadius,
            expandedGroupIndex = expandedGroupIndex,
            sliceCount = sliceCount,
            subCount = subCount
        )
        assertEquals(1, hit)
    }
}
