package app.hoot.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.squareup.gifencoder.GifEncoder
import com.squareup.gifencoder.ImageOptions
import app.hoot.model.SharedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

class NativeImageEngine : ConversionEngine {
    override val id = "native_image"
    override val displayName = "Android Image Engine"

    private val supportedSources = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif"
    )

    private val supportedTargets = listOf(
        "png", "jpg", "webp", "gif"
    )

    data class ExifTransform(
        val rotation: Float = 0f,
        val flipH: Boolean = false,
        val flipV: Boolean = false
    )

    companion object {
        private const val MAX_MERGE_WIDTH = 2048
        private const val MAX_MERGE_HEIGHT = 8192

        fun getExifTransform(orientation: Int): ExifTransform {
            var rotation = 0f
            var flipH = false
            var flipV = false
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> rotation = 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> rotation = 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> rotation = 270f
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> flipH = true
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> flipV = true
                ExifInterface.ORIENTATION_TRANSPOSE -> { rotation = 90f; flipH = true }
                ExifInterface.ORIENTATION_TRANSVERSE -> { rotation = 270f; flipH = true }
            }
            return ExifTransform(rotation, flipH, flipV)
        }

        fun shouldApplyWhiteMatte(targetFormat: String, hasAlpha: Boolean): Boolean {
            val tgt = targetFormat.lowercase(Locale.ROOT)
            return (tgt == "jpg" || tgt == "jpeg") && hasAlpha
        }
    }

    suspend fun mergeImages(
        context: Context,
        sources: List<SharedFile>,
        outputStream: OutputStream,
        targetFormat: String,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ) = withContext(Dispatchers.IO) {
        require(sources.size > 1) { "Select at least two images to merge" }
        val decoded = mutableListOf<Bitmap>()
        var merged: Bitmap? = null
        try {
            sources.forEachIndexed { index, file ->
                if (isCancelled()) return@withContext
                val bitmap = app.hoot.storage.StorageHelper.openInputStream(context, file)?.use {
                    BitmapFactory.decodeStream(it)
                } ?: throw IllegalStateException("Could not decode ${file.displayName}")
                decoded += bitmap
                onProgress(0.1f + 0.3f * (index + 1) / sources.size)
            }

            val width = minOf(MAX_MERGE_WIDTH, decoded.maxOf { it.width })
            val sizes = decoded.map { bitmap ->
                val scale = width.toFloat() / bitmap.width
                width to (bitmap.height * scale).toInt().coerceAtLeast(1)
            }
            val totalHeight = sizes.sumOf { it.second }
            require(totalHeight <= MAX_MERGE_HEIGHT) { "The merged image is too tall; select fewer or smaller images" }
            val output = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
            merged = output
            val canvas = Canvas(output)
            val target = targetFormat.lowercase(Locale.ROOT)
            if (target in listOf("jpg", "jpeg")) canvas.drawColor(Color.WHITE)
            var top = 0
            decoded.forEachIndexed { index, bitmap ->
                if (isCancelled()) return@withContext
                val scaled = Bitmap.createScaledBitmap(bitmap, sizes[index].first, sizes[index].second, true)
                canvas.drawBitmap(scaled, 0f, top.toFloat(), null)
                if (scaled !== bitmap) scaled.recycle()
                top += sizes[index].second
            }
            onProgress(0.8f)
            val format = when (target) {
                "png" -> Bitmap.CompressFormat.PNG
                "webp" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                else -> Bitmap.CompressFormat.JPEG
            }
            check(output.compress(format, if (format == Bitmap.CompressFormat.PNG) 100 else 90, outputStream)) {
                "Could not encode merged image"
            }
            outputStream.flush()
            onProgress(1f)
        } finally {
            decoded.forEach { if (!it.isRecycled) it.recycle() }
            merged?.let { if (!it.isRecycled) it.recycle() }
        }
    }


    override fun canConvert(sourceExtension: String, targetFormat: String): Boolean {
        val src = sourceExtension.lowercase(Locale.ROOT)
        val tgt = targetFormat.lowercase(Locale.ROOT)
        if (tgt == "same" || tgt == src) {
            return supportedSources.contains(src)
        }
        return supportedSources.contains(src) && supportedTargets.contains(tgt)
    }

    override fun getSupportedTargets(sourceExtension: String): List<String> {
        val src = sourceExtension.lowercase(Locale.ROOT)
        if (!supportedSources.contains(src)) return emptyList()
        return supportedTargets.filter { it != src }
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
        onProgress(0.1f)
        if (isCancelled()) return@withContext

        // 1. Determine orientation from EXIF
        var rotation = 0f
        var flipH = false
        var flipV = false
        try {
            app.hoot.storage.StorageHelper.openInputStream(context, source)?.use { stream ->
                val exif = ExifInterface(stream)
                val orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
                val transform = getExifTransform(orientation)
                rotation = transform.rotation
                flipH = transform.flipH
                flipV = transform.flipV
            }
        } catch (_: Exception) {
            // Ignore EXIF failure, proceed with standard orientation
        }

        onProgress(0.3f)
        if (isCancelled()) return@withContext

        // 2. Decode Bitmap
        var bitmap: Bitmap? = null
        app.hoot.storage.StorageHelper.openInputStream(context, source)?.use { stream ->
            bitmap = BitmapFactory.decodeStream(stream)
        }

        val original = bitmap ?: throw IllegalStateException("Could not decode image from ${source.displayName}")

        onProgress(0.5f)
        if (isCancelled()) {
            original.recycle()
            return@withContext
        }

        // 3. Apply orientation + user rotation + user flip
        val matrix = Matrix()
        val totalRotation = (rotation + (rotationDegrees?.toFloat() ?: 0f)).let { rot ->
            var r = rot % 360f
            if (r < 0f) r += 360f
            r
        }
        if (totalRotation != 0f) matrix.postRotate(totalRotation)
        val finalFlipH = flipH xor flipHorizontal
        val finalFlipV = flipV xor flipVertical
        if (finalFlipH) matrix.postScale(-1f, 1f)
        if (finalFlipV) matrix.postScale(1f, -1f)

        val orientedBitmap: Bitmap = if (!matrix.isIdentity) {
            val transformed = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
            if (transformed != original) original.recycle()
            transformed
        } else {
            original
        }

        // 4. Apply centered aspect ratio crop if requested
        val postCropBitmap: Bitmap
        if (cropAspectRatio != null && cropAspectRatio > 0.01f) {
            val srcW = orientedBitmap.width
            val srcH = orientedBitmap.height
            val currentRatio = srcW.toFloat() / srcH.toFloat()
            val (targetW, targetH) = if (currentRatio > cropAspectRatio) {
                Pair((srcH * cropAspectRatio).toInt().coerceAtLeast(1), srcH)
            } else {
                Pair(srcW, (srcW / cropAspectRatio).toInt().coerceAtLeast(1))
            }
            val startX = ((srcW - targetW) / 2).coerceIn(0, (srcW - targetW).coerceAtLeast(0))
            val startY = ((srcH - targetH) / 2).coerceIn(0, (srcH - targetH).coerceAtLeast(0))
            val cropped = Bitmap.createBitmap(orientedBitmap, startX, startY, targetW, targetH)
            if (cropped != orientedBitmap && orientedBitmap != original) {
                orientedBitmap.recycle()
            }
            postCropBitmap = cropped
        } else {
            postCropBitmap = orientedBitmap
        }

        // 4.5 Apply Background Removal if requested
        val postBgBitmap: Bitmap = if (removeBackground) {
            val removed = BackgroundRemover.apply(postCropBitmap, tolerance = 25)
            if (removed != postCropBitmap && postCropBitmap != original) {
                postCropBitmap.recycle()
            }
            removed
        } else {
            postCropBitmap
        }

        // 5. Apply resizing options (Percent or Target Width)
        var finalBitmap = postBgBitmap
        if (resizePercent != null && resizePercent > 0 && resizePercent != 100) {
            val scale = resizePercent / 100f
            val scaledW = (postBgBitmap.width * scale).toInt().coerceAtLeast(1)
            val scaledH = (postBgBitmap.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(postBgBitmap, scaledW, scaledH, true)
            if (scaled != postBgBitmap && postBgBitmap != original) {
                postBgBitmap.recycle()
            }
            finalBitmap = scaled
        } else if (targetWidth != null && postBgBitmap.width > targetWidth) {
            val scale = targetWidth.toFloat() / postBgBitmap.width
            val scaledH = (postBgBitmap.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(postBgBitmap, targetWidth, scaledH, true)
            if (scaled != postBgBitmap && postBgBitmap != original) {
                postBgBitmap.recycle()
            }
            finalBitmap = scaled
        }

        onProgress(0.75f)
        if (isCancelled()) {
            finalBitmap.recycle()
            return@withContext
        }

        // 6. Compress to target format
        val resolvedFormat = if (targetFormat.lowercase(Locale.ROOT) == "same") {
            val ext = source.extension.lowercase(Locale.ROOT)
            if (ext in listOf("jpg", "jpeg", "png", "webp")) ext else "jpg"
        } else {
            targetFormat.lowercase(Locale.ROOT)
        }

        val isJpg = resolvedFormat in listOf("jpg", "jpeg")
        val format = when (resolvedFormat) {
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
            // GIF uses the dedicated encoder below; this placeholder is never compressed as JPEG.
            "gif" -> Bitmap.CompressFormat.JPEG
            else -> Bitmap.CompressFormat.JPEG
        }

        // Clean white matte for JPG if source has alpha
        val bitmapToCompress: Bitmap
        if (shouldApplyWhiteMatte(resolvedFormat, finalBitmap.hasAlpha())) {
            val matted = Bitmap.createBitmap(finalBitmap.width, finalBitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(matted)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(finalBitmap, 0f, 0f, null)
            bitmapToCompress = matted
        } else {
            bitmapToCompress = finalBitmap
        }

        if (resolvedFormat == "gif") {
            var workingBitmap = bitmapToCompress
            var encoded = encodeStaticGif(workingBitmap)
            val maxBytes = targetMaxMb?.toLong()?.times(1024L * 1024L)
            while (maxBytes != null && encoded.size > maxBytes && workingBitmap.width > 64 && workingBitmap.height > 64) {
                val scaled = Bitmap.createScaledBitmap(
                    workingBitmap,
                    (workingBitmap.width * 0.85f).toInt().coerceAtLeast(1),
                    (workingBitmap.height * 0.85f).toInt().coerceAtLeast(1),
                    true
                )
                if (workingBitmap !== bitmapToCompress) workingBitmap.recycle()
                workingBitmap = scaled
                encoded = encodeStaticGif(workingBitmap)
            }
            outputStream.write(encoded)
            if (workingBitmap !== bitmapToCompress) workingBitmap.recycle()
        } else if (targetMaxMb != null) {
            val maxBytes = targetMaxMb.toLong() * 1024L * 1024L
            var quality = 90
            var workingBitmap = bitmapToCompress
            val byteStream = java.io.ByteArrayOutputStream()
            workingBitmap.compress(format, quality, byteStream)

            while (byteStream.size() > maxBytes) {
                if (format != Bitmap.CompressFormat.PNG && quality > 35) {
                    quality -= 15
                } else if (workingBitmap.width > 200 && workingBitmap.height > 200) {
                    val nextW = (workingBitmap.width * 0.85f).toInt()
                    val nextH = (workingBitmap.height * 0.85f).toInt()
                    val scaled = Bitmap.createScaledBitmap(workingBitmap, nextW, nextH, true)
                    if (workingBitmap != bitmapToCompress) workingBitmap.recycle()
                    workingBitmap = scaled
                } else {
                    break
                }
                byteStream.reset()
                workingBitmap.compress(format, quality, byteStream)
            }
            outputStream.write(byteStream.toByteArray())
            if (workingBitmap != bitmapToCompress) workingBitmap.recycle()
        } else {
            val quality = if (format == Bitmap.CompressFormat.PNG) 100 else 88
            bitmapToCompress.compress(format, quality, outputStream)
        }
        outputStream.flush()

        if (bitmapToCompress != finalBitmap) {
            bitmapToCompress.recycle()
        }
        finalBitmap.recycle()

        onProgress(1.0f)
    }

    private fun encodeStaticGif(bitmap: Bitmap): ByteArray {
        val rgb = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(rgb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        // GIF has no alpha in this encoder; composite transparent pixels over white.
        for (i in rgb.indices) {
            val color = rgb[i]
            val alpha = Color.alpha(color)
            if (alpha < 255) {
                val inverse = 255 - alpha
                val red = (Color.red(color) * alpha + 255 * inverse) / 255
                val green = (Color.green(color) * alpha + 255 * inverse) / 255
                val blue = (Color.blue(color) * alpha + 255 * inverse) / 255
                rgb[i] = Color.rgb(red, green, blue)
            } else {
                rgb[i] = Color.rgb(Color.red(color), Color.green(color), Color.blue(color))
            }
        }
        val bytes = java.io.ByteArrayOutputStream()
        GifEncoder(bytes, bitmap.width, bitmap.height, 0)
            .addImage(rgb, bitmap.width, ImageOptions())
            .finishEncoding()
        return bytes.toByteArray()
    }
}
