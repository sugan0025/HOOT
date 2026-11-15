package app.hoot.ui.blob

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.hoot.model.BlobTone
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

object BlobatarLayout {
    const val FaceX = 52.55f
    const val BodyCy = 51.18f
    const val PlumpX = 1.08f
    const val PlumpY = 0.92f
    const val AnchorY = 84.14f

    const val EyeRestLX = 41.8f
    const val EyeRestRX = 63.3f
    const val EyeRestY = 44.8f
    const val EyeRx = 3.7f
    const val EyeRy = 5.9f
    const val EyeMaxX = 4.7f
    const val EyeMaxY = 3.9f

    val EyeColor = Color(0xFF0F0E18)
    val BlushColor = Color(0x80FF8CAA)

    fun getToneColors(tone: BlobTone): Triple<Color, Color, Color> = when (tone) {
        BlobTone.Idle -> Triple(Color(0xFFA499F7), Color(0xFF8471E2), Color(0xFF6A62C9))
        BlobTone.Alert -> Triple(Color(0xFFB8ADFF), Color(0xFFA499F7), Color(0xFF8E82E0))
        BlobTone.Anticipation -> Triple(Color(0xFFE5E3FF), Color(0xFFCECAFF), Color(0xFFB8B3F5))
        BlobTone.Busy -> Triple(Color(0xFFE9E8F5), Color(0xFFD6D5E9), Color(0xFFBBB9D4))
        BlobTone.Celebration -> Triple(Color(0xFFE2DFFF), Color(0xFFD1CEFF), Color(0xFFABA6E5))
    }
}

@Composable
fun BlobatarView(
    modifier: Modifier = Modifier,
    size: Dp = 140.dp,
    tone: BlobTone = BlobTone.Idle,
    targetOffset: Offset? = null,
    mouthOpen: Float = 0f,
    isThinking: Boolean = false,
    isCelebrating: Boolean = false,
    onPress: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    // 1. Idle breathing cycle: Anchor-grounded volume-preserving breathing rhythm
    val ambientTransition = rememberInfiniteTransition(label = "blob_ambient")
    val breatheProgress by ambientTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathe_progress"
    )
    val breatheScaleY = 1.0f + 0.034f * breatheProgress
    val breatheScaleX = 1.0f - 0.018f * breatheProgress

    // 2. Celebratory hop & sparkle rotation
    val celebHop by ambientTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (isCelebrating) -9f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(360, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "celeb_hop"
    )
    val sparkleAngle by ambientTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sparkle_rotation"
    )
    val sparklePulse by ambientTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sparkle_pulse"
    )

    // 3. Occasional natural eye blinking timer
    val eyeLidAnim = remember { Animatable(1.0f) }
    LaunchedEffect(isCelebrating) {
        if (isCelebrating) {
            // Celebratory joyous flutter (3 quick happy blinks)
            repeat(3) {
                eyeLidAnim.animateTo(0.12f, tween(50, easing = FastOutLinearInEasing))
                eyeLidAnim.animateTo(1.0f, tween(75, easing = LinearOutSlowInEasing))
                delay(85L)
            }
        } else {
            // Natural autonomous blinking rhythm (every 2.8s - 5.5s with occasional double-blink)
            while (true) {
                val nextBlinkDelay = Random.nextLong(2800L, 5400L)
                delay(nextBlinkDelay)
                eyeLidAnim.animateTo(0.08f, tween(50, easing = FastOutLinearInEasing))
                eyeLidAnim.animateTo(1.0f, tween(80, easing = LinearOutSlowInEasing))
                if (Random.nextFloat() < 0.24f) {
                    delay(110L)
                    eyeLidAnim.animateTo(0.08f, tween(45, easing = FastOutLinearInEasing))
                    eyeLidAnim.animateTo(1.0f, tween(75, easing = LinearOutSlowInEasing))
                }
            }
        }
    }

    // 4. Fluid eye tracking & subtle micro-glances
    var idleGlance by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(targetOffset == null) {
        if (targetOffset == null) {
            while (true) {
                delay(Random.nextLong(2800L, 5200L))
                if (Random.nextFloat() < 0.65f) {
                    val angle = Random.nextFloat() * (2f * PI.toFloat())
                    val dist = Random.nextFloat() * 0.40f
                    idleGlance = Offset(cos(angle) * dist, sin(angle) * dist)
                    delay(Random.nextLong(1000L, 2000L))
                    idleGlance = Offset.Zero
                }
            }
        } else {
            idleGlance = Offset.Zero
        }
    }

    var pokeTrigger by remember { mutableStateOf(0) }
    val pokeSpring = remember { Animatable(0f) }
    LaunchedEffect(pokeTrigger) {
        if (pokeTrigger > 0) {
            pokeSpring.animateTo(1f, tween(70, easing = FastOutLinearInEasing))
            pokeSpring.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium))
        }
    }

    val touchModifier = if (onClick != null || onPress != null) {
        modifier.pointerInput(onClick, onPress) {
            detectTapGestures(
                onPress = {
                    onPress?.invoke()
                    pokeSpring.snapTo(1f)
                    val released = tryAwaitRelease()
                    if (released) {
                        pokeSpring.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium))
                        onClick?.invoke()
                    } else {
                        pokeSpring.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium))
                    }
                }
            )
        }
    } else {
        modifier
    }

    var globalCenter by remember { mutableStateOf<Offset?>(null) }

    Canvas(
        modifier = touchModifier
            .size(size)
            .onGloballyPositioned { coordinates ->
                val pos = coordinates.positionInRoot()
                globalCenter = Offset(
                    pos.x + coordinates.size.width / 2f,
                    pos.y + coordinates.size.height / 2f
                )
            }
    ) {
        val minDim = minOf(this.size.width, this.size.height)
        val scale = minDim / 100f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val refCenter = globalCenter ?: center

        // Raw gaze computation
        val rawGazeX: Float
        val rawGazeY: Float
        if (targetOffset != null) {
            val dx = targetOffset.x - refCenter.x
            val dy = targetOffset.y - refCenter.y
            val angle = atan2(dy, dx)
            val dist = minOf(1f, sqrt(dx * dx + dy * dy) / (minDim * 0.48f))
            rawGazeX = cos(angle) * dist
            rawGazeY = sin(angle) * dist
        } else {
            rawGazeX = idleGlance.x
            rawGazeY = idleGlance.y
        }

        withTransform({
            // Center mochi coordinate system (FaceX, BodyCy) -> canvas center
            translate(center.x, center.y + celebHop)
            scale(scale, scale, Offset.Zero)
            translate(-BlobatarLayout.FaceX, -BlobatarLayout.BodyCy)
        }) {
            // Ground contact shadow with breathing responsiveness
            val shadowWidth = 52f * (1.0f - 0.05f * breatheProgress)
            val shadowAlpha = (0.35f - (if (isCelebrating) 0.15f else 0f)) * (1.0f - 0.08f * breatheProgress)
            drawOval(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF000000).copy(alpha = shadowAlpha), Color(0x00000000)),
                    center = Offset(BlobatarLayout.FaceX, 85.5f - celebHop),
                    radius = 28f
                ),
                topLeft = Offset(BlobatarLayout.FaceX - shadowWidth / 2f, 82f - celebHop),
                size = Size(shadowWidth, 10f)
            )

            // Plump transformation for mochi body with bottom anchor pivot
            val pokeSquashY = 1.0f - pokeSpring.value * 0.14f
            val pokeSquashX = 1.0f + pokeSpring.value * 0.10f

            withTransform({
                scale(
                    scaleX = BlobatarLayout.PlumpX * breatheScaleX * pokeSquashX,
                    scaleY = BlobatarLayout.PlumpY * breatheScaleY * pokeSquashY,
                    pivot = Offset(BlobatarLayout.FaceX, BlobatarLayout.AnchorY)
                )
            }) {
                drawBlobBody(tone)
            }

            // Draw celebratory sparkles around Blobatar if celebrating
            if (isCelebrating) {
                drawCelebrationAuraSparkles(
                    sparkleAngle = sparkleAngle,
                    sparklePulse = sparklePulse
                )
            }

            // Draw eyes with 3D tilt, foreshortening, and catchlight sparkles
            drawBlobEyes(
                gazeX = rawGazeX,
                gazeY = rawGazeY,
                lidFactor = eyeLidAnim.value,
                isCelebrating = isCelebrating,
                sparkleAngle = sparkleAngle
            )

            // Draw mouth
            drawBlobMouth(
                isThinking = isThinking,
                isCelebrating = isCelebrating,
                mouthOpen = mouthOpen
            )
        }
    }
}

private fun DrawScope.drawBlobBody(tone: BlobTone) {
    val x0 = BlobatarLayout.FaceX
    val w = 35.0f
    val top = 18.21f
    val bottom = 84.14f
    val belly = 56.0f
    val lowY = belly + 0.64f * (bottom - belly)
    val upY = belly - 0.56f * (belly - top)

    val p0 = Offset(x0 + w, belly)
    val p1 = Offset(x0 + w, lowY)
    val p2 = Offset(x0 + w * 0.70f, bottom)
    val p3 = Offset(x0, bottom)
    val p4 = Offset(x0 - w * 0.70f, bottom)
    val p5 = Offset(x0 - w, lowY)
    val p6 = Offset(x0 - w, belly)
    val p7 = Offset(x0 - w, upY)
    val p8 = Offset(x0 - w * 0.56f, top)
    val p9 = Offset(x0, top)
    val p10 = Offset(x0 + w * 0.56f, top)
    val p11 = Offset(x0 + w, upY)

    val bodyPath = Path().apply {
        moveTo(p0.x, p0.y)
        cubicTo(p1.x, p1.y, p2.x, p2.y, p3.x, p3.y)
        cubicTo(p4.x, p4.y, p5.x, p5.y, p6.x, p6.y)
        cubicTo(p7.x, p7.y, p8.x, p8.y, p9.x, p9.y)
        cubicTo(p10.x, p10.y, p11.x, p11.y, p0.x, p0.y)
        close()
    }

    val (hi, mid, lo) = BlobatarLayout.getToneColors(tone)
    drawPath(
        path = bodyPath,
        brush = Brush.verticalGradient(
            0.0f to hi,
            0.45f to mid,
            1.0f to lo,
            startY = top,
            endY = bottom
        )
    )

    // Soft silhouette outline
    drawPath(
        path = bodyPath,
        color = lo.copy(alpha = 0.35f),
        style = Stroke(width = 1.0f)
    )

    // Forehead specular highlight
    drawOval(
        brush = Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = 0.32f), Color.White.copy(alpha = 0f)),
            center = Offset(x0 - 2f, 32f),
            radius = 12f
        ),
        topLeft = Offset(x0 - 13f, 26f),
        size = Size(22f, 12f)
    )

    // Pink blush cheeks
    drawOval(
        color = BlobatarLayout.BlushColor,
        topLeft = Offset(36.0f, 52.5f),
        size = Size(8.8f, 5.0f)
    )
    drawOval(
        color = BlobatarLayout.BlushColor,
        topLeft = Offset(60.8f, 52.5f),
        size = Size(8.8f, 5.0f)
    )
}

private fun DrawScope.drawCelebrationAuraSparkles(
    sparkleAngle: Float,
    sparklePulse: Float
) {
    val auraSparkles = listOf(
        Triple(Offset(22f, 24f), 3.2f * sparklePulse, Color(0xFFFFF7C2)),
        Triple(Offset(82f, 20f), 3.6f * (2f - sparklePulse), Color.White),
        Triple(Offset(18f, 52f), 2.8f * sparklePulse, Color(0xFFE2DFFF)),
        Triple(Offset(86f, 50f), 3.0f * (2f - sparklePulse), Color(0xFFFFF7C2)),
        Triple(Offset(32f, 10f), 2.5f * (2f - sparklePulse), Color.White),
        Triple(Offset(72f, 12f), 3.0f * sparklePulse, Color(0xFFE2DFFF))
    )

    auraSparkles.forEachIndexed { index, (pos, radius, color) ->
        val rot = sparkleAngle + (index * 45f)
        drawStarSparkle(
            center = pos,
            radius = radius,
            rotationDeg = rot,
            color = color
        )
    }
}

private fun DrawScope.drawBlobEyes(
    gazeX: Float,
    gazeY: Float,
    lidFactor: Float,
    isCelebrating: Boolean,
    sparkleAngle: Float
) {
    // 3D Mochi Tilt and Foreshortening matching Windows Hoot
    val s = if (gazeX >= 0f) 1.0f else -1.0f
    val leftTiltDeg = gazeX.coerceIn(-1f, 1f) * 8.0f - gazeY.coerceIn(-1f, 1f) * 3.5f * s
    val rightTiltDeg = gazeX.coerceIn(-1f, 1f) * 8.0f - gazeY.coerceIn(-1f, 1f) * 3.5f * s

    val leftForeshortenX = if (gazeX > 0f) {
        1.0f - gazeX.coerceIn(0f, 1f) * 0.18f
    } else {
        1.0f + (-gazeX).coerceIn(0f, 1f) * 0.04f
    }
    val rightForeshortenX = if (gazeX < 0f) {
        1.0f - (-gazeX).coerceIn(0f, 1f) * 0.18f
    } else {
        1.0f + gazeX.coerceIn(0f, 1f) * 0.04f
    }

    val eyeLift = if (isCelebrating) 1.6f else 0f
    val lx = BlobatarLayout.EyeRestLX + gazeX * BlobatarLayout.EyeMaxX
    val rx = BlobatarLayout.EyeRestRX + gazeX * BlobatarLayout.EyeMaxX
    val ey = BlobatarLayout.EyeRestY + gazeY * BlobatarLayout.EyeMaxY - eyeLift

    // Left eye
    drawEye(
        center = Offset(lx, ey),
        tiltDeg = leftTiltDeg,
        foreshortenX = leftForeshortenX,
        lidFactor = lidFactor,
        gazeX = gazeX,
        gazeY = gazeY,
        isCelebrating = isCelebrating,
        sparkleAngle = sparkleAngle
    )

    // Right eye
    drawEye(
        center = Offset(rx, ey),
        tiltDeg = rightTiltDeg,
        foreshortenX = rightForeshortenX,
        lidFactor = lidFactor,
        gazeX = gazeX,
        gazeY = gazeY,
        isCelebrating = isCelebrating,
        sparkleAngle = sparkleAngle
    )
}

private fun DrawScope.drawEye(
    center: Offset,
    tiltDeg: Float,
    foreshortenX: Float,
    lidFactor: Float,
    gazeX: Float,
    gazeY: Float,
    isCelebrating: Boolean,
    sparkleAngle: Float
) {
    val dil = if (isCelebrating) 1.25f else 1.0f
    val rw = BlobatarLayout.EyeRx * foreshortenX * dil
    val rh = BlobatarLayout.EyeRy * lidFactor * dil

    withTransform({
        rotate(degrees = tiltDeg, pivot = center)
    }) {
        drawOval(
            color = BlobatarLayout.EyeColor,
            topLeft = Offset(center.x - rw, center.y - rh),
            size = Size(rw * 2, rh * 2)
        )
    }

    // Catchlights / Glints (visible when eye is open enough)
    if (lidFactor > 0.35f) {
        val lift = minOf(1.0f, lidFactor)

        // Windows Hoot catchlight formula with key light at (-0.4, -0.7)
        val lx = -0.4f
        val ly = -0.7f

        val glint1X = center.x + lx * 1.4f - gazeX * 0.85f
        val glint1Y = center.y + ly * 2.5f * lift - gazeY * 0.55f

        if (isCelebrating) {
            // Joyous twinkling diamond sparkle in the eye
            drawStarSparkle(
                center = Offset(glint1X, glint1Y),
                radius = 2.4f,
                rotationDeg = sparkleAngle,
                color = Color.White
            )

            // Secondary diamond twinkle
            drawStarSparkle(
                center = Offset(center.x + 1.4f - gazeX * 0.4f, center.y + 1.5f * lift - gazeY * 0.35f),
                radius = 1.4f,
                rotationDeg = -sparkleAngle * 0.8f,
                color = Color(0xFFFFF8D6)
            )
        } else {
            // Primary catchlight
            drawCircle(
                color = Color.White.copy(alpha = 0.95f),
                radius = 1.4f,
                center = Offset(glint1X, glint1Y)
            )

            // Secondary ambient glint opposite key light
            val ax = 0.5f
            val ay = 0.6f
            val glint2X = center.x + ax * 1.8f - gazeX * 0.4f
            val glint2Y = center.y + ay * 2.4f * lift - gazeY * 0.3f
            drawCircle(
                color = Color.White.copy(alpha = 0.55f),
                radius = 0.75f,
                center = Offset(glint2X, glint2Y)
            )
        }
    }
}

private fun DrawScope.drawStarSparkle(
    center: Offset,
    radius: Float,
    rotationDeg: Float = 0f,
    color: Color = Color.White
) {
    if (radius <= 0f) return
    withTransform({
        rotate(rotationDeg, center)
    }) {
        val path = Path().apply {
            val r = radius
            val c = r * 0.18f
            moveTo(center.x, center.y - r)
            quadraticTo(center.x + c, center.y - c, center.x + r, center.y)
            quadraticTo(center.x + c, center.y + c, center.x, center.y + r)
            quadraticTo(center.x - c, center.y + c, center.x - r, center.y)
            quadraticTo(center.x - c, center.y - c, center.x, center.y - r)
            close()
        }
        drawPath(path, color)
        drawCircle(
            color = Color.White,
            radius = radius * 0.32f,
            center = center
        )
    }
}

private fun DrawScope.drawBlobMouth(
    isThinking: Boolean,
    isCelebrating: Boolean,
    mouthOpen: Float = 0f
) {
    val mouthY = 56.8f
    val x0 = BlobatarLayout.FaceX

    if (mouthOpen > 0.02f) {
        // Dynamic open mouth for swallowing files (copied directly from Windows Hoot BlobatarRenderer.cs)
        val clampedOpen = mouthOpen.coerceIn(0f, 1f)
        val rw = 3.9f
        val rh = 1.0f + 4.0f * clampedOpen
        val mouthCenterY = 57.0f

        // Dark throat cavity (#392B5A)
        drawOval(
            color = Color(0xFF392B5A),
            topLeft = Offset(x0 - rw, mouthCenterY - rh),
            size = Size(rw * 2f, rh * 2f)
        )
        // Soft violet rim (#6E58A8)
        drawOval(
            color = Color(0xFF6E58A8),
            topLeft = Offset(x0 - rw, mouthCenterY - rh),
            size = Size(rw * 2f, rh * 2f),
            style = Stroke(width = 1.2f)
        )
        return
    }

    if (isThinking) {
        // Small "o" mouth
        drawOval(
            color = BlobatarLayout.EyeColor,
            topLeft = Offset(x0 - 2.5f, mouthY - 1.5f),
            size = Size(5.0f, 4.0f)
        )
    } else if (isCelebrating) {
        // Joyous wide happy mouth with rosy tongue depth
        val happyPath = Path().apply {
            moveTo(x0 - 4.4f, mouthY - 1.2f)
            quadraticTo(x0, mouthY + 6.2f, x0 + 4.4f, mouthY - 1.2f)
            close()
        }
        drawPath(
            path = happyPath,
            color = BlobatarLayout.EyeColor
        )
        // Radiant rosy tongue
        val tonguePath = Path().apply {
            moveTo(x0 - 2.8f, mouthY + 2.4f)
            quadraticTo(x0, mouthY + 5.8f, x0 + 2.8f, mouthY + 2.4f)
            close()
        }
        drawPath(
            path = tonguePath,
            color = Color(0xFFFF7299)
        )
    }
    // Idle rest: No mouth line drawn. Mouth only opens when file swallow comes!
}
