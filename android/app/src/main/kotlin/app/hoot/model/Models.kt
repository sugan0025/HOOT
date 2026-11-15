package app.hoot.model

import android.net.Uri

data class SharedFile(
    val uri: Uri,
    val displayName: String,
    val size: Long,
    val mimeType: String,
    val extension: String,
    val relativePath: String? = null,
    val filePath: String? = null
)

data class ConversionBatch(
    val files: List<SharedFile>
) {
    val isMultiple: Boolean get() = files.size > 1
    val primaryFile: SharedFile get() = files.first()
    val totalSize: Long get() = files.sumOf { it.size }
}

enum class BlobTone {
    Idle,         // #8471E2
    Alert,        // #A499F7
    Anticipation, // #CECAFF
    Busy,         // #D6D5E9
    Celebration   // #D1CEFF
}

data class DonutSlice(
    val id: String,
    val label: String,
    val caption: String? = null,
    val targetFormat: String,
    val groupIndex: Int = 0,
    val isEnabled: Boolean = true,
    val isGroup: Boolean = false,
    val subSlices: List<DonutSlice> = emptyList(),
    val resizePercent: Int? = null,
    val targetWidth: Int? = null,
    val targetMaxMb: Int? = null,
    val rotationDegrees: Int? = null,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val cropAspectRatio: Float? = null
)

sealed class ConversionUiState {
    data object Ready : ConversionUiState()
    data class Converting(val targetFormat: String, val progress: Float) : ConversionUiState()
    data class Success(
        val outputUri: Uri,
        val outputName: String,
        val isBesideOriginal: Boolean,
        val destinationPath: String
    ) : ConversionUiState()
    data class Error(val message: String) : ConversionUiState()
}
