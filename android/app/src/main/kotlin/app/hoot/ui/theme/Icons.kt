package app.hoot.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun CheckIcon(
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = Color.White
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.2f, h * 0.5f)
            lineTo(w * 0.42f, h * 0.72f)
            lineTo(w * 0.82f, h * 0.28f)
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = 3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
fun RefreshIcon(
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    tint: Color = Color.White
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val center = Offset(w / 2f, h / 2f)
        val radius = w * 0.38f

        drawArc(
            color = tint,
            startAngle = 45f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
            style = Stroke(width = 2.5f, cap = StrokeCap.Round)
        )

        // Arrow head
        val arrowX = center.x + radius * 0.707f
        val arrowY = center.y - radius * 0.707f
        val arrow = Path().apply {
            moveTo(arrowX - 4f, arrowY - 6f)
            lineTo(arrowX + 2f, arrowY)
            lineTo(arrowX - 4f, arrowY + 6f)
        }
        drawPath(path = arrow, color = tint, style = Stroke(width = 2.5f, cap = StrokeCap.Round))
    }
}

@Composable
fun ShareIcon(
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    tint: Color = Color.White
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height

        val p1 = Offset(w * 0.75f, h * 0.25f)
        val p2 = Offset(w * 0.25f, h * 0.5f)
        val p3 = Offset(w * 0.75f, h * 0.75f)

        drawLine(color = tint, start = p2, end = p1, strokeWidth = 2.5f, cap = StrokeCap.Round)
        drawLine(color = tint, start = p2, end = p3, strokeWidth = 2.5f, cap = StrokeCap.Round)

        drawCircle(color = tint, radius = 3.5f, center = p1)
        drawCircle(color = tint, radius = 3.5f, center = p2)
        drawCircle(color = tint, radius = 3.5f, center = p3)
    }
}

@Composable
fun FileBadgeIcon(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    tint: Color = Color.White,
    extension: String = ""
) {
    val ext = extension.uppercase()
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height

        when {
            ext in listOf("PNG", "JPG", "JPEG", "WEBP", "GIF", "SVG", "BMP") -> {
                // Crisp Image / Landscape Frame
                val r = 2.5f
                val frame = Path().apply {
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(1.5f, 1.5f, w - 1.5f, h - 1.5f, androidx.compose.ui.geometry.CornerRadius(r, r)))
                }
                drawPath(frame, tint, style = Stroke(width = 1.8f))
                // Sun
                drawCircle(color = tint, radius = w * 0.12f, center = Offset(w * 0.32f, h * 0.35f))
                // Mountain peaks
                val mtn = Path().apply {
                    moveTo(w * 0.20f, h * 0.75f)
                    lineTo(w * 0.45f, h * 0.48f)
                    lineTo(w * 0.62f, h * 0.65f)
                    lineTo(w * 0.72f, h * 0.55f)
                    lineTo(w * 0.85f, h * 0.75f)
                }
                drawPath(mtn, tint, style = Stroke(width = 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            ext in listOf("MP3", "WAV", "M4A", "FLAC", "OGG", "AAC") -> {
                // Musical Eighth Note
                val headR = w * 0.18f
                drawCircle(color = tint, radius = headR, center = Offset(w * 0.35f, h * 0.72f))
                drawLine(color = tint, start = Offset(w * 0.35f + headR - 1f, h * 0.72f), end = Offset(w * 0.35f + headR - 1f, h * 0.22f), strokeWidth = 1.8f, cap = StrokeCap.Round)
                val flag = Path().apply {
                    moveTo(w * 0.35f + headR - 1f, h * 0.22f)
                    quadraticTo(w * 0.78f, h * 0.30f, w * 0.75f, h * 0.52f)
                }
                drawPath(flag, tint, style = Stroke(width = 1.8f, cap = StrokeCap.Round))
            }
            ext in listOf("MP4", "MKV", "MOV", "WEBM", "AVI") -> {
                // Video Play triangle inside rounded rectangle
                val r = 2.5f
                val frame = Path().apply {
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(1.5f, 1.5f, w - 1.5f, h - 1.5f, androidx.compose.ui.geometry.CornerRadius(r, r)))
                }
                drawPath(frame, tint, style = Stroke(width = 1.8f))
                val play = Path().apply {
                    moveTo(w * 0.40f, h * 0.32f)
                    lineTo(w * 0.68f, h * 0.50f)
                    lineTo(w * 0.40f, h * 0.68f)
                    close()
                }
                drawPath(play, tint)
            }
            else -> {
                // Folded document icon
                val fold = w * 0.34f
                val docPath = Path().apply {
                    moveTo(1.5f, 1.5f)
                    lineTo(w - fold, 1.5f)
                    lineTo(w - 1.5f, fold)
                    lineTo(w - 1.5f, h - 1.5f)
                    lineTo(1.5f, h - 1.5f)
                    close()
                }
                drawPath(
                    path = docPath,
                    color = tint,
                    style = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                val foldPath = Path().apply {
                    moveTo(w - fold, 1.5f)
                    lineTo(w - fold, fold)
                    lineTo(w - 1.5f, fold)
                }
                drawPath(
                    path = foldPath,
                    color = tint,
                    style = Stroke(width = 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }
    }
}
