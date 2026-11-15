package app.hoot.ui.main

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private enum class SnowflakeTier {
    LARGE_DENDRITE,
    MEDIUM_STAR,
    MICRO_CRYSTAL
}

private data class RealSnowflake(
    val initialX: Float,
    val initialY: Float,
    val radiusDp: Float,
    val fallCycles: Int,
    val swayDp: Float,
    val swayCycles: Int,
    val rotationTurns: Int,
    val initialAngleRad: Float,
    val phase: Float,
    val alpha: Float,
    val tier: SnowflakeTier
)

@Composable
fun SnowfallEffect(
    modifier: Modifier = Modifier,
    flakeCount: Int = 42
) {
    val snowflakes = remember(flakeCount) {
        val rng = Random(1337)
        List(flakeCount) { i ->
            val tier = when {
                i % 10 == 0 -> SnowflakeTier.LARGE_DENDRITE
                i % 3 == 0 -> SnowflakeTier.MEDIUM_STAR
                else -> SnowflakeTier.MICRO_CRYSTAL
            }

            val radius = when (tier) {
                SnowflakeTier.LARGE_DENDRITE -> rng.nextFloat() * 2.0f + 4.5f
                SnowflakeTier.MEDIUM_STAR -> rng.nextFloat() * 1.5f + 3.0f
                SnowflakeTier.MICRO_CRYSTAL -> rng.nextFloat() * 1.0f + 1.2f
            }

            RealSnowflake(
                initialX = rng.nextFloat(),
                initialY = rng.nextFloat(),
                radiusDp = radius,
                fallCycles = rng.nextInt(1, 3),
                swayDp = rng.nextFloat() * 10f + 4f,
                swayCycles = rng.nextInt(1, 4),
                rotationTurns = rng.nextInt(-1, 2),
                initialAngleRad = rng.nextFloat() * (2f * PI.toFloat()),
                phase = rng.nextFloat() * 20f,
                alpha = when (tier) {
                    SnowflakeTier.LARGE_DENDRITE -> rng.nextFloat() * 0.12f + 0.78f
                    SnowflakeTier.MEDIUM_STAR -> rng.nextFloat() * 0.16f + 0.58f
                    SnowflakeTier.MICRO_CRYSTAL -> rng.nextFloat() * 0.18f + 0.30f
                },
                tier = tier
            )
        }
    }

    val transition = rememberInfiniteTransition(label = "Snowfall")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 36000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "SnowfallProgress"
    )
    val density = LocalDensity.current.density

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val tau = 2f * PI.toFloat()

        snowflakes.forEach { flake ->
            val radius = flake.radiusDp * density
            val travelSpan = h + radius * 2f
            val totalY = -radius + ((flake.initialY * h + progress * flake.fallCycles * travelSpan) % travelSpan)
            val sway = sin(progress * flake.swayCycles * tau + flake.phase) * flake.swayDp * density
            val totalX = ((flake.initialX * w + sway) % w + w) % w

            val fadeRange = h * 0.08f
            val edgeFade = minOf(1f, (totalY + radius) / fadeRange, (h + radius - totalY) / fadeRange).coerceIn(0f, 1f)
            val finalAlpha = flake.alpha * edgeFade
            if (finalAlpha <= 0.02f) return@forEach

            val currentRotation = flake.initialAngleRad + progress * flake.rotationTurns * tau

            // A faint halo gives the small crystals contrast without covering the wallpaper.
            drawCircle(
                color = Color(0xFFDCE5FF).copy(alpha = finalAlpha * 0.10f),
                radius = radius * 1.65f,
                center = Offset(totalX, totalY)
            )

            when (flake.tier) {
                SnowflakeTier.LARGE_DENDRITE -> {
                    drawRealDendriteSnowflake(
                        cx = totalX,
                        cy = totalY,
                        r = radius,
                        rotationRad = currentRotation,
                        alpha = finalAlpha
                    )
                }
                SnowflakeTier.MEDIUM_STAR -> {
                    drawMediumStarSnowflake(
                        cx = totalX,
                        cy = totalY,
                        r = radius,
                        rotationRad = currentRotation,
                        alpha = finalAlpha
                    )
                }
                SnowflakeTier.MICRO_CRYSTAL -> {
                    drawMicroIceCrystal(
                        cx = totalX,
                        cy = totalY,
                        r = radius,
                        rotationRad = currentRotation,
                        alpha = finalAlpha
                    )
                }
            }
        }
    }
}

/**
 * Draws a genuine 6-arm dendrite crystalline snowflake with:
 * - 6 main spine arms (60° apart)
 * - Inner sub-branches at 45°
 * - Outer sub-branches at 45°
 * - Central crystalline hexagon core
 */
private fun DrawScope.drawRealDendriteSnowflake(
    cx: Float,
    cy: Float,
    r: Float,
    rotationRad: Float,
    alpha: Float
) {
    val spineColor = Color(0xFFF3F0FF).copy(alpha = alpha)
    val branchColor = Color(0xFFE2DCFF).copy(alpha = alpha * 0.92f)
    val strokeW = (r * 0.12f).coerceIn(1.2f, 2.2f)
    val branchStrokeW = (r * 0.09f).coerceIn(1.0f, 1.8f)

    // 1. Central 6-sided crystalline core
    val coreR = r * 0.22f
    val corePath = Path().apply {
        for (i in 0 until 6) {
            val a = rotationRad + i * (PI.toFloat() / 3f)
            val px = cx + coreR * cos(a)
            val py = cy + coreR * sin(a)
            if (i == 0) moveTo(px, py) else lineTo(px, py)
        }
        close()
    }
    drawPath(
        path = corePath,
        color = Color.White.copy(alpha = alpha * 0.85f),
        style = Stroke(width = branchStrokeW, join = StrokeJoin.Round)
    )

    // 2. 6 main dendrite branches with dual V-needles
    for (k in 0 until 6) {
        val theta = rotationRad + k * (PI.toFloat() / 3f)
        val cosT = cos(theta)
        val sinT = sin(theta)

        // Main spine arm
        val tipX = cx + r * cosT
        val tipY = cy + r * sinT
        drawLine(
            color = spineColor,
            start = Offset(cx, cy),
            end = Offset(tipX, tipY),
            strokeWidth = strokeW,
            cap = StrokeCap.Round
        )

        // Branch 1: Inner needles at 0.44r
        val d1 = r * 0.44f
        val bx1 = cx + d1 * cosT
        val by1 = cy + d1 * sinT
        val l1 = r * 0.28f

        val phi1A = theta + (PI.toFloat() / 4f)
        val phi1B = theta - (PI.toFloat() / 4f)
        drawLine(
            color = branchColor,
            start = Offset(bx1, by1),
            end = Offset(bx1 + l1 * cos(phi1A), by1 + l1 * sin(phi1A)),
            strokeWidth = branchStrokeW,
            cap = StrokeCap.Round
        )
        drawLine(
            color = branchColor,
            start = Offset(bx1, by1),
            end = Offset(bx1 + l1 * cos(phi1B), by1 + l1 * sin(phi1B)),
            strokeWidth = branchStrokeW,
            cap = StrokeCap.Round
        )

        // Branch 2: Outer needles at 0.74r
        val d2 = r * 0.74f
        val bx2 = cx + d2 * cosT
        val by2 = cy + d2 * sinT
        val l2 = r * 0.20f

        val phi2A = theta + (PI.toFloat() / 4f)
        val phi2B = theta - (PI.toFloat() / 4f)
        drawLine(
            color = branchColor,
            start = Offset(bx2, by2),
            end = Offset(bx2 + l2 * cos(phi2A), by2 + l2 * sin(phi2A)),
            strokeWidth = branchStrokeW,
            cap = StrokeCap.Round
        )
        drawLine(
            color = branchColor,
            start = Offset(bx2, by2),
            end = Offset(bx2 + l2 * cos(phi2B), by2 + l2 * sin(phi2B)),
            strokeWidth = branchStrokeW,
            cap = StrokeCap.Round
        )
    }

    // Central gleam dot
    drawCircle(
        color = Color.White.copy(alpha = alpha),
        radius = r * 0.10f,
        center = Offset(cx, cy)
    )
}

/**
 * Midground star snowflake: 6 arms with single outer branchlets
 */
private fun DrawScope.drawMediumStarSnowflake(
    cx: Float,
    cy: Float,
    r: Float,
    rotationRad: Float,
    alpha: Float
) {
    val color = Color(0xFFECE7FF).copy(alpha = alpha)
    val strokeW = (r * 0.12f).coerceIn(1.0f, 1.6f)

    for (k in 0 until 6) {
        val theta = rotationRad + k * (PI.toFloat() / 3f)
        val cosT = cos(theta)
        val sinT = sin(theta)

        val tipX = cx + r * cosT
        val tipY = cy + r * sinT
        drawLine(
            color = color,
            start = Offset(cx, cy),
            end = Offset(tipX, tipY),
            strokeWidth = strokeW,
            cap = StrokeCap.Round
        )

        // Single side branchlet at 0.55r
        val d = r * 0.55f
        val bx = cx + d * cosT
        val by = cy + d * sinT
        val l = r * 0.25f

        val phiA = theta + (PI.toFloat() / 4f)
        val phiB = theta - (PI.toFloat() / 4f)
        drawLine(
            color = color,
            start = Offset(bx, by),
            end = Offset(bx + l * cos(phiA), by + l * sin(phiA)),
            strokeWidth = strokeW * 0.85f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(bx, by),
            end = Offset(bx + l * cos(phiB), by + l * sin(phiB)),
            strokeWidth = strokeW * 0.85f,
            cap = StrokeCap.Round
        )
    }

    drawCircle(
        color = Color.White.copy(alpha = alpha * 0.9f),
        radius = r * 0.14f,
        center = Offset(cx, cy)
    )
}

/**
 * Background micro ice crystal: 6-pointed diamond glint
 */
private fun DrawScope.drawMicroIceCrystal(
    cx: Float,
    cy: Float,
    r: Float,
    rotationRad: Float,
    alpha: Float
) {
    val color = Color.White.copy(alpha = alpha)
    for (branch in 0 until 3) {
        val angle = rotationRad + branch * (PI.toFloat() / 3f)
        val dx = cos(angle) * r
        val dy = sin(angle) * r
        drawLine(
            color = color,
            start = Offset(cx - dx, cy - dy),
            end = Offset(cx + dx, cy + dy),
            strokeWidth = 1.0f,
            cap = StrokeCap.Round
        )
    }
}
