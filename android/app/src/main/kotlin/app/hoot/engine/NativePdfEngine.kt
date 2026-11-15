package app.hoot.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import androidx.exifinterface.media.ExifInterface
import app.hoot.model.SharedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.Locale

class NativePdfEngine : ConversionEngine {
    override val id = "native_pdf"
    override val displayName = "Android PDF Engine"

    companion object {
        const val MAX_PAGE_DIMENSION_PT = 1400f

        /**
         * FitImage mode: computes exact page bounds dynamically matching the
         * source image aspect ratio and resolution with zero empty letterbox margins.
         */
        fun calculateFitImageDimensions(
            imgWidth: Float,
            imgHeight: Float,
            maxDimPt: Float = MAX_PAGE_DIMENSION_PT
        ): Pair<Int, Int> {
            require(imgWidth > 0f && imgHeight > 0f) { "Image dimensions must be positive" }
            val maxDim = maxOf(imgWidth, imgHeight)
            val scale = if (maxDim > maxDimPt) maxDimPt / maxDim else 1f
            val pageWidth = maxOf(1, (imgWidth * scale).toInt())
            val pageHeight = maxOf(1, (imgHeight * scale).toInt())
            return Pair(pageWidth, pageHeight)
        }
    }

    private val supportedImageSources = setOf(
        "jpg", "jpeg", "png", "webp", "bmp", "heic", "heif", "avif"
    )

    override fun canConvert(sourceExtension: String, targetFormat: String): Boolean {
        val src = sourceExtension.lowercase(Locale.ROOT)
        val tgt = targetFormat.lowercase(Locale.ROOT)
        return (supportedImageSources.contains(src) && tgt == "pdf") ||
                (src == "pdf" && tgt in listOf("png", "jpg", "txt"))
    }

    override fun getSupportedTargets(sourceExtension: String): List<String> {
        val src = sourceExtension.lowercase(Locale.ROOT)
        return when {
            supportedImageSources.contains(src) -> listOf("pdf")
            src == "pdf" -> listOf("png", "jpg", "txt")
            else -> emptyList()
        }
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
        val src = source.extension.lowercase(Locale.ROOT)
        val tgt = targetFormat.lowercase(Locale.ROOT)

        if (supportedImageSources.contains(src) && tgt == "pdf") {
            convertImagesToPdf(context, listOf(source), outputStream, onProgress, isCancelled)
        } else if (src == "pdf" && tgt in listOf("png", "jpg")) {
            convertPdfToImage(context, source, outputStream, tgt, onProgress, isCancelled, resizePercent, targetWidth, targetMaxMb)
        } else if (src == "pdf" && tgt == "txt") {
            extractTextFromPdf(context, source, outputStream, onProgress, isCancelled)
        } else {
            throw UnsupportedOperationException("Conversion $src -> $tgt not implemented in NativePdfEngine")
        }
    }

    private fun extractTextFromPdf(
        context: Context,
        source: SharedFile,
        outputStream: OutputStream,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ) {
        onProgress(0.2f)
        if (isCancelled()) return
        try {
            com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
            context.contentResolver.openInputStream(source.uri)?.use { stream ->
                val document = com.tom_roush.pdfbox.pdmodel.PDDocument.load(stream)
                try {
                    onProgress(0.5f)
                    if (isCancelled()) return
                    val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
                    val text = stripper.getText(document)
                    val bytes = text.toByteArray(Charsets.UTF_8)
                    outputStream.write(bytes)
                    outputStream.flush()
                    onProgress(1.0f)
                } finally {
                    document.close()
                }
            } ?: throw IllegalStateException("Could not open PDF file: ${source.displayName}")
        } catch (e: Throwable) {
            throw IllegalStateException("PDF text extraction failed: ${e.message}", e)
        }
    }

    private fun convertPdfToImage(
        context: Context,
        source: SharedFile,
        outputStream: OutputStream,
        targetFormat: String,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
        resizePercent: Int?,
        targetWidth: Int?,
        targetMaxMb: Int?
    ) {
        onProgress(0.15f)
        if (isCancelled()) return

        val pfd = context.contentResolver.openFileDescriptor(source.uri, "r")
            ?: throw IllegalStateException("Could not open PDF file: ${source.displayName}")

        try {
            val renderer = android.graphics.pdf.PdfRenderer(pfd)
            if (renderer.pageCount == 0) {
                renderer.close()
                throw IllegalStateException("PDF contains no pages")
            }

            val page = renderer.openPage(0)
            onProgress(0.4f)
            if (isCancelled()) {
                page.close()
                renderer.close()
                return
            }

            // High-DPI scale (2.5x for crisp 180 DPI print-quality rendering)
            val dpiScale = 2.5f
            val baseW = (page.width * dpiScale).toInt()
            val baseH = (page.height * dpiScale).toInt()

            val bitmap = Bitmap.createBitmap(baseW, baseH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            page.close()
            renderer.close()

            onProgress(0.7f)
            if (isCancelled()) {
                bitmap.recycle()
                return
            }

            var finalBitmap = bitmap
            if (resizePercent != null && resizePercent > 0 && resizePercent != 100) {
                val scale = resizePercent / 100f
                val scaledW = (bitmap.width * scale).toInt().coerceAtLeast(1)
                val scaledH = (bitmap.height * scale).toInt().coerceAtLeast(1)
                finalBitmap = Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true)
                if (finalBitmap != bitmap) bitmap.recycle()
            } else if (targetWidth != null && bitmap.width > targetWidth) {
                val scale = targetWidth.toFloat() / bitmap.width
                val scaledH = (bitmap.height * scale).toInt().coerceAtLeast(1)
                finalBitmap = Bitmap.createScaledBitmap(bitmap, targetWidth, scaledH, true)
                if (finalBitmap != bitmap) bitmap.recycle()
            }

            val isPng = targetFormat.lowercase(Locale.ROOT) == "png"
            val format = if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val quality = if (isPng) 100 else 90

            finalBitmap.compress(format, quality, outputStream)
            outputStream.flush()
            finalBitmap.recycle()
            onProgress(1.0f)
        } finally {
            pfd.close()
        }
    }

    suspend fun convertMultipleImagesToPdf(
        context: Context,
        sources: List<SharedFile>,
        outputStream: OutputStream,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ) = withContext(Dispatchers.IO) {
        convertImagesToPdf(context, sources, outputStream, onProgress, isCancelled)
    }

    private fun convertImagesToPdf(
        context: Context,
        sources: List<SharedFile>,
        outputStream: OutputStream,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ) {
        val document = PdfDocument()
        val total = sources.size

        try {
            sources.forEachIndexed { index, source ->
                if (isCancelled()) return

                var rotation = 0f
                var flipH = false
                var flipV = false
                try {
                    context.contentResolver.openInputStream(source.uri)?.use { stream ->
                        val exif = ExifInterface(stream)
                        val orientation = exif.getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL
                        )
                        val transform = NativeImageEngine.getExifTransform(orientation)
                        rotation = transform.rotation
                        flipH = transform.flipH
                        flipV = transform.flipV
                    }
                } catch (_: Exception) {}

                var bitmap: Bitmap? = null
                context.contentResolver.openInputStream(source.uri)?.use { stream ->
                    bitmap = BitmapFactory.decodeStream(stream)
                }

                val original = bitmap ?: return@forEachIndexed

                val finalBitmap: Bitmap
                if (rotation != 0f || flipH || flipV) {
                    val matrix = Matrix()
                    if (rotation != 0f) matrix.postRotate(rotation)
                    if (flipH) matrix.postScale(-1f, 1f)
                    if (flipV) matrix.postScale(1f, -1f)
                    finalBitmap = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
                    if (finalBitmap != original) original.recycle()
                } else {
                    finalBitmap = original
                }

                // FitImage mode (exact match to Windows Hoot default):
                // PDF page dimensions match the image aspect ratio exactly, full-bleed with no letterbox white space!
                val (pageWidth, pageHeight) = calculateFitImageDimensions(
                    finalBitmap.width.toFloat(),
                    finalBitmap.height.toFloat()
                )

                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                val page = document.startPage(pageInfo)

                val canvas: Canvas = page.canvas
                val destRect = RectF(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat())
                canvas.drawBitmap(finalBitmap, null, destRect, null)
                document.finishPage(page)
                finalBitmap.recycle()

                val progress = 0.1f + 0.8f * ((index + 1).toFloat() / total)
                onProgress(progress)
            }

            if (isCancelled()) return
            document.writeTo(outputStream)
            outputStream.flush()
            onProgress(1.0f)
        } finally {
            document.close()
        }
    }
}
