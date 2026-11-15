package app.hoot.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfDocument
import android.os.Build
import app.hoot.model.SharedFile
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.GZIPInputStream

class SvgVectorEngine : ConversionEngine {
    override val id = "svg_vector"
    override val displayName = "AndroidSVG Vector Engine"

    private val supportedSources = setOf("svg", "svgz")
    private val supportedTargets = listOf("png", "jpg", "webp", "pdf")

    override fun canConvert(sourceExtension: String, targetFormat: String): Boolean {
        val src = sourceExtension.lowercase(Locale.ROOT)
        val tgt = targetFormat.lowercase(Locale.ROOT)
        if (tgt == "same" || tgt == src) return false
        return supportedSources.contains(src) && supportedTargets.contains(tgt)
    }

    override fun getSupportedTargets(sourceExtension: String): List<String> {
        val src = sourceExtension.lowercase(Locale.ROOT)
        if (!supportedSources.contains(src)) return emptyList()
        return supportedTargets
    }

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
        onProgress(0.15f)
        if (isCancelled()) return@withContext

        val srcExt = source.extension.lowercase(Locale.ROOT)
        val isGzip = srcExt == "svgz"

        val svg: SVG = context.contentResolver.openInputStream(source.uri)?.use { rawStream ->
            val effectiveStream: InputStream = if (isGzip) GZIPInputStream(rawStream) else rawStream
            SVG.getFromInputStream(effectiveStream)
        } ?: throw IllegalStateException("Could not parse SVG document from ${source.displayName}")

        onProgress(0.4f)
        if (isCancelled()) return@withContext

        // Calculate base dimensions from SVG document or viewBox
        var docW = svg.documentWidth
        var docH = svg.documentHeight
        if (docW <= 0f || docH <= 0f) {
            val vb = svg.documentViewBox
            if (vb != null && vb.width() > 0f && vb.height() > 0f) {
                docW = vb.width()
                docH = vb.height()
            } else {
                docW = 1024f
                docH = 1024f
            }
        }

        val resolvedTgt = if (targetFormat.lowercase(Locale.ROOT) == "same") "png" else targetFormat.lowercase(Locale.ROOT)

        if (resolvedTgt == "pdf") {
            // Render directly as vector paths onto PdfDocument Canvas!
            renderSvgToPdf(svg, docW, docH, outputStream, onProgress, isCancelled)
            return@withContext
        }

        // Target is an image format (PNG, JPG, WEBP)
        var targetW = docW
        var targetH = docH

        if (resizePercent != null && resizePercent > 0 && resizePercent != 100) {
            val scale = resizePercent / 100f
            targetW *= scale
            targetH *= scale
        } else if (targetWidth != null && targetWidth > 0) {
            val scale = targetWidth.toFloat() / docW
            targetW = targetWidth.toFloat()
            targetH = docH * scale
        }

        val bmpW = targetW.toInt().coerceAtLeast(1)
        val bmpH = targetH.toInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val isJpg = resolvedTgt in listOf("jpg", "jpeg")
        if (isJpg) {
            canvas.drawColor(Color.WHITE)
        }

        // Scale SVG rendering to match target resolution
        svg.documentWidth = bmpW.toFloat()
        svg.documentHeight = bmpH.toFloat()
        svg.renderToCanvas(canvas)

        onProgress(0.7f)
        if (isCancelled()) {
            bitmap.recycle()
            return@withContext
        }

        // Apply rotation or flips if requested
        val matrix = Matrix()
        if (rotationDegrees != null && rotationDegrees != 0) {
            matrix.postRotate(rotationDegrees.toFloat())
        }
        if (flipHorizontal) matrix.postScale(-1f, 1f)
        if (flipVertical) matrix.postScale(1f, -1f)

        val orientedBitmap: Bitmap = if (!matrix.isIdentity) {
            val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (transformed != bitmap) bitmap.recycle()
            transformed
        } else {
            bitmap
        }

        // Centered aspect ratio crop if requested
        val postCropBitmap: Bitmap
        if (cropAspectRatio != null && cropAspectRatio > 0.01f) {
            val sw = orientedBitmap.width
            val sh = orientedBitmap.height
            val currentRatio = sw.toFloat() / sh.toFloat()
            val (cw, ch) = if (currentRatio > cropAspectRatio) {
                Pair((sh * cropAspectRatio).toInt().coerceAtLeast(1), sh)
            } else {
                Pair(sw, (sw / cropAspectRatio).toInt().coerceAtLeast(1))
            }
            val sx = ((sw - cw) / 2).coerceIn(0, (sw - cw).coerceAtLeast(0))
            val sy = ((sh - ch) / 2).coerceIn(0, (sh - ch).coerceAtLeast(0))
            val cropped = Bitmap.createBitmap(orientedBitmap, sx, sy, cw, ch)
            if (cropped != orientedBitmap && orientedBitmap != bitmap) {
                orientedBitmap.recycle()
            }
            postCropBitmap = cropped
        } else {
            postCropBitmap = orientedBitmap
        }

        val compressFormat = when (resolvedTgt) {
            "jpg", "jpeg" -> Bitmap.CompressFormat.JPEG
            "png" -> Bitmap.CompressFormat.PNG
            "webp" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
            }
            else -> Bitmap.CompressFormat.PNG
        }

        if (targetMaxMb != null) {
            val maxBytes = targetMaxMb.toLong() * 1024L * 1024L
            var quality = 90
            var workingBitmap = postCropBitmap
            val byteStream = ByteArrayOutputStream()
            workingBitmap.compress(compressFormat, quality, byteStream)

            while (byteStream.size() > maxBytes) {
                if (compressFormat != Bitmap.CompressFormat.PNG && quality > 35) {
                    quality -= 15
                } else if (workingBitmap.width > 200 && workingBitmap.height > 200) {
                    val nw = (workingBitmap.width * 0.85f).toInt()
                    val nh = (workingBitmap.height * 0.85f).toInt()
                    val scaled = Bitmap.createScaledBitmap(workingBitmap, nw, nh, true)
                    if (workingBitmap != postCropBitmap) workingBitmap.recycle()
                    workingBitmap = scaled
                } else {
                    break
                }
                byteStream.reset()
                workingBitmap.compress(compressFormat, quality, byteStream)
            }
            outputStream.write(byteStream.toByteArray())
            if (workingBitmap != postCropBitmap) workingBitmap.recycle()
        } else {
            val quality = if (compressFormat == Bitmap.CompressFormat.PNG) 100 else 92
            postCropBitmap.compress(compressFormat, quality, outputStream)
        }
        outputStream.flush()

        postCropBitmap.recycle()
        if (orientedBitmap != postCropBitmap && orientedBitmap != bitmap) {
            orientedBitmap.recycle()
        }
        onProgress(1.0f)
    }

    private fun renderSvgToPdf(
        svg: SVG,
        docW: Float,
        docH: Float,
        outputStream: OutputStream,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ) {
        val maxDim = 1400f
        val maxSource = maxOf(docW, docH)
        val scale = if (maxSource > maxDim) maxDim / maxSource else 1f
        val pageW = (docW * scale).toInt().coerceAtLeast(1)
        val pageH = (docH * scale).toInt().coerceAtLeast(1)

        val doc = PdfDocument()
        try {
            val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, 1).create()
            val page = doc.startPage(pageInfo)
            val canvas = page.canvas

            svg.documentWidth = pageW.toFloat()
            svg.documentHeight = pageH.toFloat()
            svg.renderToCanvas(canvas)

            doc.finishPage(page)
            onProgress(0.85f)
            if (isCancelled()) return

            doc.writeTo(outputStream)
            outputStream.flush()
            onProgress(1.0f)
        } finally {
            doc.close()
        }
    }
}
