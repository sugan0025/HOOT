package app.hoot.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import app.hoot.model.SharedFile
import app.hoot.storage.StorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.Locale

/** Converts raster artwork to editable SVG paths using color quantization and run tracing. */
class RasterTraceEngine : ConversionEngine {
    override val id = "raster_trace"
    override val displayName = "Raster to SVG Trace"

    private val sources = setOf("jpg", "jpeg", "png", "webp", "bmp", "heic", "heif", "avif", "gif")

    override fun canConvert(sourceExtension: String, targetFormat: String): Boolean =
        sourceExtension.lowercase(Locale.ROOT) in sources && targetFormat.equals("svg", ignoreCase = true)

    override fun getSupportedTargets(sourceExtension: String): List<String> =
        if (sourceExtension.lowercase(Locale.ROOT) in sources) listOf("svg") else emptyList()

    override suspend fun convert(
        context: Context,
        source: SharedFile,
        outputStream: OutputStream,
        targetFormat: String,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
        resizePercent: Int?,
        targetWidth: Int?,
        targetMaxMb: Int?,
        rotationDegrees: Int?,
        flipHorizontal: Boolean,
        flipVertical: Boolean,
        cropAspectRatio: Float?,
        removeBackground: Boolean
    ) = withContext(Dispatchers.IO) {
        onProgress(0.1f)
        val decoded = StorageHelper.openInputStream(context, source)?.use { BitmapFactory.decodeStream(it) }
            ?: throw IllegalStateException("Could not decode image from ${source.displayName}")
        var bitmap = decoded
        try {
            if (resizePercent != null && resizePercent > 0 && resizePercent != 100) {
                val scaled = Bitmap.createScaledBitmap(bitmap,
                    (bitmap.width * resizePercent / 100f).toInt().coerceAtLeast(1),
                    (bitmap.height * resizePercent / 100f).toInt().coerceAtLeast(1), true)
                if (scaled !== bitmap) bitmap.recycle()
                bitmap = scaled
            } else if (targetWidth != null && targetWidth > 0 && bitmap.width > targetWidth) {
                val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth,
                    (bitmap.height * targetWidth.toFloat() / bitmap.width).toInt().coerceAtLeast(1), true)
                if (scaled !== bitmap) bitmap.recycle()
                bitmap = scaled
            }

            if (rotationDegrees != null && rotationDegrees % 360 != 0 || flipHorizontal || flipVertical) {
                val matrix = Matrix().apply {
                    rotationDegrees?.let { postRotate(it.toFloat()) }
                    if (flipHorizontal) postScale(-1f, 1f)
                    if (flipVertical) postScale(1f, -1f)
                }
                val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (transformed !== bitmap) bitmap.recycle()
                bitmap = transformed
            }

            if (cropAspectRatio != null && cropAspectRatio > 0.01f) {
                val ratio = bitmap.width.toFloat() / bitmap.height
                val cropW = if (ratio > cropAspectRatio) (bitmap.height * cropAspectRatio).toInt().coerceAtLeast(1) else bitmap.width
                val cropH = if (ratio > cropAspectRatio) bitmap.height else (bitmap.width / cropAspectRatio).toInt().coerceAtLeast(1)
                val cropped = Bitmap.createBitmap(bitmap, (bitmap.width - cropW) / 2, (bitmap.height - cropH) / 2, cropW, cropH)
                if (cropped !== bitmap) bitmap.recycle()
                bitmap = cropped
            }

            // Bound path complexity and memory on large photographs while preserving aspect ratio.
            val scale = minOf(1f, MAX_TRACE_DIMENSION.toFloat() / maxOf(bitmap.width, bitmap.height))
            if (scale < 1f) {
                val scaled = Bitmap.createScaledBitmap(bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1), true)
                if (scaled !== bitmap) bitmap.recycle()
                bitmap = scaled
            }

            onProgress(0.35f)
            if (isCancelled()) return@withContext
            val paths = traceRuns(bitmap, isCancelled)
            if (isCancelled()) return@withContext
            onProgress(0.85f)
            outputStream.write("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ${bitmap.width} ${bitmap.height}\">".toByteArray(Charsets.UTF_8))
            paths.forEach { (color, data) ->
                if (data.isNotEmpty()) {
                    outputStream.write("<path fill=\"$color\" d=\"".toByteArray(Charsets.UTF_8))
            outputStream.write(data.toString().toByteArray(Charsets.UTF_8))
                    outputStream.write("\"/>".toByteArray(Charsets.UTF_8))
                }
            }
            outputStream.write("</svg>".toByteArray(Charsets.UTF_8))
            outputStream.flush()
            onProgress(1f)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun traceRuns(bitmap: Bitmap, isCancelled: () -> Boolean): Map<String, StringBuilder> {
        val colors = LinkedHashMap<String, StringBuilder>()
        val active = HashMap<String, MutableList<Run>>()
        for (y in 0 until bitmap.height) {
            if (isCancelled()) return colors
            val next = HashMap<String, MutableList<Run>>()
            var start = 0
            var last = quantize(bitmap.getPixel(0, y))
            for (x in 1..bitmap.width) {
                val color = if (x < bitmap.width) quantize(bitmap.getPixel(x, y)) else null
                if (color != last) {
                    val key = last.toSvgColor()
                    val matches = active[key]
                    val prior = matches?.firstOrNull { it.x == start && it.width == x - start }
                    val run = if (prior != null) {
                        prior.height += 1
                        prior
                    } else Run(start, y, x - start, 1)
                    next.getOrPut(key) { mutableListOf() }.add(run)
                    if (prior == null) {
                        colors.getOrPut(key) { StringBuilder() }.append('M').append(start).append(' ').append(y)
                            .append('h').append(x - start).append("v1h-").append(x - start).append('z')
                    } else {
                        // Replace the prior one-row rectangle with one vertically merged rectangle.
                        val data = colors.getValue(key)
                        val old = "M${run.x} ${run.y}h${run.width}v${run.height - 1}h-${run.width}z"
                        val new = "M${run.x} ${run.y}h${run.width}v${run.height}h-${run.width}z"
                        val index = data.lastIndexOf(old)
                        if (index >= 0) data.replace(index, index + old.length, new)
                    }
                    start = x
                    last = color ?: last
                }
            }
            active.clear()
            active.putAll(next)
        }
        return colors
    }

    private data class Run(val x: Int, val y: Int, val width: Int, var height: Int)

    private fun quantize(color: Int): Int {
        fun channel(value: Int) = (value * 4 / 256).coerceIn(0, 3) * 85
        val a = Color.alpha(color)
        val r = (Color.red(color) * a + 255 * (255 - a)) / 255
        val g = (Color.green(color) * a + 255 * (255 - a)) / 255
        val b = (Color.blue(color) * a + 255 * (255 - a)) / 255
        return Color.rgb(channel(r), channel(g), channel(b))
    }

    private fun Int.toSvgColor(): String = String.format(Locale.ROOT, "#%02x%02x%02x", Color.red(this), Color.green(this), Color.blue(this))

    private companion object {
        const val MAX_TRACE_DIMENSION = 384
    }
}
