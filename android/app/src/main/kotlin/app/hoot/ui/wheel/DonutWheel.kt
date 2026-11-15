package app.hoot.ui.wheel

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.hoot.audio.HootSoundManager
import app.hoot.model.BlobTone
import app.hoot.model.DonutSlice
import app.hoot.ui.blob.BlobatarView
import kotlin.math.PI
import kotlin.math.sqrt

@Composable
fun DonutWheel(
    modifier: Modifier = Modifier,
    slices: List<DonutSlice>,
    hoveredIndex: Int = -1,
    expandedGroupIndex: Int = -1,
    hoveredSubIndex: Int = -1,
    tone: BlobTone = BlobTone.Idle,
    targetOffset: Offset? = null,
    mouthOpen: Float = 0f,
    flipTrigger: Int = 0,
    progress: Float = 0f,
    isConverting: Boolean = false,
    isCelebrating: Boolean = false,
    centerTitle: String? = null,
    centerSubtitle: String? = null,
    centerFlipHint: String? = null,
    onFlip: () -> Unit = {},
    onSliceClick: (Int) -> Unit = {},
    onSubSliceClick: (Int, Int) -> Unit = { _, _ -> }
) {
    val flipAnim = remember { Animatable(1f) }
    val expandAnim = remember { Animatable(0f) }

    LaunchedEffect(flipTrigger) {
        if (flipTrigger > 0) {
            // Exact Windows Hoot: Wheel flip plays ONLY PlayFlipSound()
            HootSoundManager.playFlip()
            flipAnim.animateTo(
                targetValue = 0f,
                animationSpec = tween(120, easing = FastOutLinearInEasing)
            )
            flipAnim.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
        }
    }

    LaunchedEffect(expandedGroupIndex) {
        if (expandedGroupIndex >= 0) {
            expandAnim.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        } else {
            expandAnim.animateTo(
                targetValue = 0f,
                animationSpec = tween(140, easing = FastOutLinearInEasing)
            )
        }
    }

    LaunchedEffect(hoveredIndex) {
        if (hoveredIndex >= 0) {
            HootSoundManager.playTick(hoveredIndex)
        }
    }

    LaunchedEffect(hoveredSubIndex) {
        if (hoveredSubIndex >= 0) {
            HootSoundManager.playTick(hoveredSubIndex)
        }
    }

    LaunchedEffect(isCelebrating) {
        if (isCelebrating) {
            HootSoundManager.playChime()
        }
    }

    val badgeScale by animateFloatAsState(
        targetValue = if (isCelebrating) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "badgeScale"
    )

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(slices, expandedGroupIndex, isConverting) {
                    if (!isConverting) {
                        detectTapGestures { tapOffset ->
                            val currentCenter = Offset(size.width / 2f, size.height / 2f)
                            val currentOuter = minOf(size.width, size.height) * 0.355f

                            if (expandedGroupIndex in slices.indices) {
                                val groupSlice = slices[expandedGroupIndex]
                                val hitSub = hitTestSubSlice(
                                    touchPos = tapOffset,
                                    center = currentCenter,
                                    outerRadius = currentOuter,
                                    expandedGroupIndex = expandedGroupIndex,
                                    sliceCount = slices.size,
                                    subCount = groupSlice.subSlices.size
                                )
                                if (hitSub >= 0) {
                                    HootSoundManager.playDrop()
                                    onSubSliceClick(expandedGroupIndex, hitSub)
                                    return@detectTapGestures
                                }
                            }

                            val hit = hitTestSlice(
                                touchPos = tapOffset,
                                center = currentCenter,
                                outerRadius = currentOuter,
                                sliceCount = slices.size
                            )
                            if (hit >= 0) {
                                HootSoundManager.playDrop()
                                onSliceClick(hit)
                            } else {
                                val distToCenter = sqrt((tapOffset.x - currentCenter.x) * (tapOffset.x - currentCenter.x) + (tapOffset.y - currentCenter.y) * (tapOffset.y - currentCenter.y))
                                val currentInner = currentOuter * 0.42f
                                if (distToCenter < currentInner) {
                                    HootSoundManager.playFlip()
                                    onFlip()
                                }
                            }
                        }
                    }
                }
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val outerRadius = minOf(size.width, size.height) * 0.355f
            val innerRadius = outerRadius * 0.42f // Exact Windows Hoot HoleRatio

            // Windows Hoot exact subwheel proportions (DonutMenu.Groups.cs)
            val subGap = 6.dp.toPx()
            val subBand = 46.dp.toPx()
            val popOutGroup = 10f
            val subInnerRadius = outerRadius + popOutGroup + subGap
            val subOuterRadius = subInnerRadius + subBand

            // 1. Ambient liquid glass back-glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0x388471E2),
                        Color(0x15A499F7),
                        Color(0x00000000)
                    ),
                    center = center,
                    radius = outerRadius * 1.35f
                ),
                radius = outerRadius * 1.35f,
                center = center
            )

            // 2. Liquid Glass Main Slices
            if (slices.isNotEmpty() && !isConverting) {
                val sliceCount = slices.size
                val baseSliceSpanRad = (2f * PI / sliceCount).toFloat()

                slices.forEachIndexed { index, slice ->
                    val isHovered = index == hoveredIndex
                    val isExpanded = index == expandedGroupIndex
                    // Slice i is centred on i * span, starting at 12 o'clock (midAngleRad = 0)
                    val midAngleRad = index * baseSliceSpanRad
                    val sweepSpanRad = baseSliceSpanRad * flipAnim.value

                    // Pop out displacement when hovered or when fanned out
                    val popOut = if (isHovered) 14f else if (isExpanded) 10f else 0f
                    val offsetPoint = DonutWedgeGeometry.pointAt(Offset.Zero, popOut, midAngleRad)
                    val sliceCenter = Offset(center.x + offsetPoint.x, center.y + offsetPoint.y)

                    drawLiquidGlassSlice(
                        center = sliceCenter,
                        innerRadius = innerRadius,
                        outerRadius = outerRadius + (if (isHovered) 10f else if (isExpanded) 6f else 0f),
                        midAngleRad = midAngleRad,
                        spanRad = sweepSpanRad,
                        isHovered = isHovered || isExpanded,
                        label = slice.label,
                        caption = slice.caption,
                        isGroup = slice.isGroup
                    )
                }

                // 2.5 Subwheel Small Cards Fan (Concentric annular ring sectors outside group slice)
                if (expandedGroupIndex in slices.indices && expandAnim.value > 0.001f) {
                    val groupSlice = slices[expandedGroupIndex]
                    val subSlices = groupSlice.subSlices
                    val subCount = subSlices.size

                    if (subCount > 0) {
                        val parentMidRad = expandedGroupIndex * baseSliceSpanRad
                        val midRadius = (subInnerRadius + subOuterRadius) / 2f
                        val subSpanRad = DonutWedgeGeometry.calculateSubSpanRad(baseSliceSpanRad, subCount, midRadius)

                        for (k in 0 until subCount) {
                            // Sequential card fan-out stagger (Windows Hoot DonutMenu.Groups.cs)
                            val kDelay = if (subCount > 1) (k.toFloat() / (subCount - 1)) * 0.16f else 0f
                            val tk = ((expandAnim.value - kDelay) / (1f - kDelay + 0.0001f)).coerceIn(0f, 1f)
                            if (tk <= 0.001f) continue

                            val subSlice = subSlices[k]
                            val isSubHovered = k == hoveredSubIndex
                            val subMidRad = parentMidRad + (k - (subCount - 1) / 2.0f) * subSpanRad

                            drawLiquidGlassSubSlice(
                                center = center,
                                innerRadius = subInnerRadius,
                                outerRadius = subOuterRadius,
                                midAngleRad = subMidRad,
                                spanRad = subSpanRad * tk,
                                isHovered = isSubHovered,
                                alpha = tk,
                                label = subSlice.label,
                                caption = subSlice.caption
                            )
                        }
                    }
                }
            }

            // 3. Center Solid White Card Disc & Aperture (Matching Windows HOOT Screenshots)
            drawCircle(
                color = Color.White,
                radius = innerRadius - 2f,
                center = center
            )
            drawCircle(
                color = Color(0x22000000),
                radius = innerRadius - 2f,
                center = center,
                style = Stroke(width = 1.5f)
            )

            // Center text drawn on white card disc directly below Blobatar
            if (!centerTitle.isNullOrBlank()) {
                drawContext.canvas.nativeCanvas.apply {
                    val titlePaint = Paint().apply {
                        color = android.graphics.Color.rgb(15, 23, 42) // #0F172A
                        textSize = 34f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        textAlign = Paint.Align.CENTER
                        isAntiAlias = true
                    }
                    val subtitlePaint = Paint().apply {
                        color = android.graphics.Color.rgb(100, 116, 139) // #64748B
                        textSize = 24f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                        textAlign = Paint.Align.CENTER
                        isAntiAlias = true
                    }
                    val hintPaint = Paint().apply {
                        color = android.graphics.Color.rgb(71, 85, 105) // #475569
                        textSize = 24f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                        textAlign = Paint.Align.CENTER
                        isAntiAlias = true
                    }

                    val titleY = center.y + innerRadius * 0.28f
                    drawText(centerTitle, center.x, titleY, titlePaint)

                    val subText = centerSubtitle ?: (if (expandedGroupIndex >= 0) "Move outward to choose" else "Esc to close")
                    val subY = center.y + innerRadius * 0.48f
                    drawText(subText, center.x, subY, subtitlePaint)

                    val flipText = centerFlipHint ?: "↺ Tab front"
                    val flipY = center.y + innerRadius * 0.68f
                    drawText(flipText, center.x, flipY, hintPaint)
                }
            }

            // 4. Progress Mode
            if (isConverting || isCelebrating) {
                drawCircle(
                    color = Color(0x35FFFFFF),
                    radius = innerRadius - 4f,
                    center = center,
                    style = Stroke(width = 7f)
                )

                val arcColor = if (isCelebrating) Color(0xFF4ADE80) else Color(0xFF9D8EFF)
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            arcColor.copy(alpha = 0.5f),
                            arcColor,
                            Color.White
                        ),
                        center = center
                    ),
                    startAngle = -90f,
                    sweepAngle = if (isCelebrating) 360f else (progress * 360f).coerceAtLeast(15f),
                    useCenter = false,
                    topLeft = Offset(center.x - innerRadius + 4f, center.y - innerRadius + 4f),
                    size = Size((innerRadius - 4f) * 2f, (innerRadius - 4f) * 2f),
                    style = Stroke(width = 8f, cap = StrokeCap.Round)
                )
            }

            // 5. Celebration Pop Badge
            if (badgeScale > 0.05f) {
                val badgeRadius = 24f * badgeScale
                val badgeCenter = Offset(center.x + innerRadius - 4f, center.y - innerRadius + 4f)

                drawCircle(
                    color = Color(0xFF22C55E),
                    radius = badgeRadius,
                    center = badgeCenter
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.6f),
                    radius = badgeRadius,
                    center = badgeCenter,
                    style = Stroke(width = 2f)
                )

                val checkPath = Path().apply {
                    moveTo(badgeCenter.x - 7f * badgeScale, badgeCenter.y)
                    lineTo(badgeCenter.x - 2f * badgeScale, badgeCenter.y + 5f * badgeScale)
                    lineTo(badgeCenter.x + 8f * badgeScale, badgeCenter.y - 5f * badgeScale)
                }
                drawPath(
                    path = checkPath,
                    color = Color.White,
                    style = Stroke(width = 3.5f * badgeScale, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }

        // Center Mascot: Exact Living Blobatar in aperture placed comfortably in the top half
        BlobatarView(
            modifier = Modifier.offset(y = (-20).dp),
            size = 82.dp,
            tone = tone,
            targetOffset = targetOffset,
            mouthOpen = mouthOpen,
            isThinking = isConverting,
            isCelebrating = isCelebrating,
            onPress = {
                HootSoundManager.playBlobPress()
            }
        )
    }
}

private fun DrawScope.drawLiquidGlassSlice(
    center: Offset,
    innerRadius: Float,
    outerRadius: Float,
    midAngleRad: Float,
    spanRad: Float,
    isHovered: Boolean,
    label: String,
    caption: String?,
    isGroup: Boolean
) {
    if (spanRad <= 0.001f) return

    val path = DonutWedgeGeometry.createWedgePath(
        center = center,
        innerRadius = innerRadius,
        outerRadius = outerRadius,
        midAngleRad = midAngleRad,
        spanRad = spanRad,
        gapPx = DonutWedgeGeometry.DEFAULT_GAP_PX,
        cornerRadiusPx = 10.dp.toPx()
    )

    if (isHovered) {
        // Vibrant medium purple gradient for active/expanded group slice (Screenshot 1 & 3)
        val hoverBrush = Brush.verticalGradient(
            colors = listOf(
                Color(0xFF7F77DD),
                Color(0xFF6C63FF),
                Color(0xFF5B50E6)
            ),
            startY = center.y - outerRadius,
            endY = center.y + outerRadius
        )
        drawPath(path = path, brush = hoverBrush)

        // Accent border
        drawPath(path = path, color = Color(0x406C63FF), style = Stroke(width = 4.0f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path = path, color = Color(0xFFC7D2FE), style = Stroke(width = 1.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    } else {
        // Windows HOOT Liquid Glass: Solid Pure White Cards with subtle cool shading
        val glassBrush = Brush.verticalGradient(
            colors = listOf(
                Color(0xFFFFFFFF),
                Color(0xFFF9F9FB),
                Color(0xFFEDEDF3)
            ),
            startY = center.y - outerRadius,
            endY = center.y + outerRadius
        )
        drawPath(path = path, brush = glassBrush)

        // Delicate, high-contrast dark border stroke
        drawPath(path = path, color = Color(0x35000000), style = Stroke(width = 1.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    // Windows HOOT Group Chevron Mark (Screenshot 1, 2, 3)
    if (isGroup) {
        val chevronPath = DonutWedgeGeometry.createGroupMarkPath(
            center = center,
            outerRadius = outerRadius,
            midAngleRad = midAngleRad,
            scale = 1.0f
        )
        val markColor = if (isHovered) Color.White else Color(0xFF475569)
        drawPath(path = chevronPath, color = markColor, style = Stroke(width = 2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    // Slice label and caption placed at slice center
    val midRadius = (innerRadius + outerRadius) / 2f
    val textPoint = DonutWedgeGeometry.pointAt(center, midRadius, midAngleRad)
    val textX = textPoint.x
    val textY = textPoint.y

    val hasCaption = !caption.isNullOrBlank()

    drawContext.canvas.nativeCanvas.apply {
        val titlePaint = Paint().apply {
            color = if (isHovered) android.graphics.Color.WHITE else android.graphics.Color.rgb(15, 23, 42) // #0F172A
            textSize = if (isHovered) 36f else 33f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            if (isHovered) {
                setShadowLayer(14f, 0f, 2f, android.graphics.Color.argb(160, 108, 99, 255))
            } else {
                setShadowLayer(2f, 0f, 1f, android.graphics.Color.argb(30, 0, 0, 0))
            }
        }

        val captionPaint = Paint().apply {
            color = if (isHovered) android.graphics.Color.rgb(224, 231, 255) else android.graphics.Color.rgb(100, 116, 139) // #64748B
            textSize = if (isHovered) 21f else 19f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }

        val textBounds = android.graphics.Rect()
        titlePaint.getTextBounds(label, 0, label.length, textBounds)
        val titleHeight = textBounds.height().toFloat()

        if (hasCaption) {
            val labelY = textY - 4f
            drawText(label, textX, labelY, titlePaint)
            val captionY = labelY + titleHeight + 11f
            drawText(caption, textX, captionY, captionPaint)
        } else {
            val labelY = textY + (titleHeight / 2f)
            drawText(label, textX, labelY, titlePaint)
        }
    }
}

private fun DrawScope.drawLiquidGlassSubSlice(
    center: Offset,
    innerRadius: Float,
    outerRadius: Float,
    midAngleRad: Float,
    spanRad: Float,
    isHovered: Boolean,
    alpha: Float,
    label: String,
    caption: String?
) {
    if (spanRad <= 0.001f || alpha <= 0.01f) return

    // Build the authentic concentric arc wedge matching Windows Hoot
    val path = DonutWedgeGeometry.createWedgePath(
        center = center,
        innerRadius = innerRadius,
        outerRadius = outerRadius,
        midAngleRad = midAngleRad,
        spanRad = spanRad,
        gapPx = DonutWedgeGeometry.DEFAULT_GAP_PX,
        cornerRadiusPx = 8.dp.toPx()
    )

    if (isHovered) {
        val hoverBrush = Brush.verticalGradient(
            colors = listOf(
                Color(0xFF7F77DD).copy(alpha = alpha),
                Color(0xFF6C63FF).copy(alpha = alpha),
                Color(0xFF5B50E6).copy(alpha = alpha)
            ),
            startY = center.y - outerRadius,
            endY = center.y + outerRadius
        )
        drawPath(path = path, brush = hoverBrush)

        drawPath(path = path, color = Color(0x306C63FF).copy(alpha = 0.35f * alpha), style = Stroke(width = 5.0f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path = path, color = Color(0x606C63FF).copy(alpha = 0.65f * alpha), style = Stroke(width = 2.0f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path = path, color = Color(0xFFF0EDFF).copy(alpha = alpha), style = Stroke(width = 1.0f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    } else {
        // Windows HOOT Pure White Subwheels
        val glassBrush = Brush.verticalGradient(
            colors = listOf(
                Color(0xFFFFFFFF).copy(alpha = alpha),
                Color(0xFFF9F9FB).copy(alpha = alpha),
                Color(0xFFEDEDF3).copy(alpha = alpha)
            ),
            startY = center.y - outerRadius,
            endY = center.y + outerRadius
        )
        drawPath(path = path, brush = glassBrush)

        drawPath(path = path, color = Color(0x30000000).copy(alpha = 0.35f * alpha), style = Stroke(width = 1.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    val midRadius = (innerRadius + outerRadius) / 2f
    val textPoint = DonutWedgeGeometry.pointAt(center, midRadius, midAngleRad)
    val textX = textPoint.x
    val textY = textPoint.y

    val hasCaption = !caption.isNullOrBlank()

    drawContext.canvas.nativeCanvas.apply {
        val titlePaint = Paint().apply {
            color = if (isHovered) android.graphics.Color.WHITE else android.graphics.Color.argb((255 * alpha).toInt().coerceIn(0, 255), 15, 23, 42) // #0F172A
            textSize = if (isHovered) 25f else 23f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            if (isHovered) {
                setShadowLayer(12f, 0f, 2f, android.graphics.Color.argb(160, 108, 99, 255))
            } else {
                setShadowLayer(2f, 0f, 1f, android.graphics.Color.argb(25, 0, 0, 0))
            }
        }

        val captionPaint = Paint().apply {
            color = if (isHovered) android.graphics.Color.rgb(224, 231, 255) else android.graphics.Color.argb((255 * alpha).toInt().coerceIn(0, 255), 100, 116, 139) // #64748B
            textSize = 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }

        val textBounds = android.graphics.Rect()
        titlePaint.getTextBounds(label, 0, label.length, textBounds)
        val titleHeight = textBounds.height().toFloat()

        if (hasCaption) {
            val labelY = textY - 3f
            drawText(label, textX, labelY, titlePaint)
            val captionY = labelY + titleHeight + 9f
            drawText(caption, textX, captionY, captionPaint)
        } else {
            val labelY = textY + (titleHeight / 2f)
            drawText(label, textX, labelY, titlePaint)
        }
    }
}

/**
 * Hit test for main slices with exact clockwise-from-12-o'clock sector coverage.
 * Slice i is centred on i * span.
 */
fun hitTestSlice(
    touchPos: Offset,
    center: Offset,
    outerRadius: Float,
    sliceCount: Int
): Int {
    if (sliceCount <= 0) return -1
    val innerRadius = outerRadius * 0.42f

    val p = DonutWedgeGeometry.Point(touchPos.x, touchPos.y)
    val c = DonutWedgeGeometry.Point(center.x, center.y)
    val dist = (p - c).length()

    if (dist < innerRadius || dist > outerRadius + 24f) {
        return -1
    }

    val angle = DonutWedgeGeometry.angleOf(center, p)
    val span = (2f * PI / sliceCount).toFloat()
    val index = (Math.floor((angle + span / 2.0) / span).toInt()) % sliceCount
    return if (index in 0 until sliceCount) index else -1
}

/**
 * Hit test for subwheels in the outer ring of an expanded group.
 * Matches Windows Hoot DonutGeometry.SubIndexAt.
 */
fun hitTestSubSlice(
    touchPos: Offset,
    center: Offset,
    outerRadius: Float,
    expandedGroupIndex: Int,
    sliceCount: Int,
    subCount: Int
): Int {
    if (expandedGroupIndex < 0 || sliceCount <= 0 || subCount <= 0) return -1
    val popOut = 10f
    val subGap = 6f * (outerRadius / 150f)
    val subBand = 46f * (outerRadius / 150f)
    val subInnerRadius = outerRadius + popOut + subGap
    val subOuterRadius = subInnerRadius + subBand

    val p = DonutWedgeGeometry.Point(touchPos.x, touchPos.y)
    val c = DonutWedgeGeometry.Point(center.x, center.y)
    val dist = (p - c).length()

    if (dist < subInnerRadius - 14f || dist > subOuterRadius + 22f) return -1

    val touchAngle = DonutWedgeGeometry.angleOf(center, p)
    val parentSpan = (2f * PI / sliceCount).toFloat()
    val parentMid = expandedGroupIndex * parentSpan
    val midRadius = (subInnerRadius + subOuterRadius) / 2f
    val subSpan = DonutWedgeGeometry.calculateSubSpanRad(parentSpan, subCount, midRadius)

    var delta = touchAngle - parentMid
    while (delta > PI) delta -= (2f * PI).toFloat()
    while (delta < -PI) delta += (2f * PI).toFloat()

    val sub = Math.floor(delta / subSpan + subCount / 2.0).toInt()
    return if (sub in 0 until subCount) sub else -1
}
