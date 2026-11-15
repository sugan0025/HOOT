package app.hoot.engine

import android.content.Context
import app.hoot.model.SharedFile
import java.io.OutputStream

interface ConversionEngine {
    val id: String
    val displayName: String

    fun canConvert(sourceExtension: String, targetFormat: String): Boolean
    fun getSupportedTargets(sourceExtension: String): List<String>

    suspend fun convert(
        context: Context,
        source: SharedFile,
        outputStream: OutputStream,
        targetFormat: String,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
        resizePercent: Int? = null,
        targetWidth: Int? = null,
        targetMaxMb: Int? = null,
        rotationDegrees: Int? = null,
        flipHorizontal: Boolean = false,
        flipVertical: Boolean = false,
        cropAspectRatio: Float? = null,
        removeBackground: Boolean = false
    )
}
