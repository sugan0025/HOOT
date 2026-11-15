package app.hoot.ui.main

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hoot.audio.HootSoundManager
import app.hoot.engine.EngineRegistry
import app.hoot.model.BlobTone
import app.hoot.model.ConversionUiState
import app.hoot.model.DonutSlice
import app.hoot.model.SharedFile
import app.hoot.storage.StorageHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class HootViewModel : ViewModel() {

    private val _currentFile = MutableStateFlow<SharedFile?>(null)
    val currentFile: StateFlow<SharedFile?> = _currentFile.asStateFlow()
    private val _currentFileCount = MutableStateFlow(0)
    val currentFileCount: StateFlow<Int> = _currentFileCount.asStateFlow()
    private var currentFiles: List<SharedFile> = emptyList()

    private val _faceIndex = MutableStateFlow(0)
    val faceIndex: StateFlow<Int> = _faceIndex.asStateFlow()

    private val _face1Slices = MutableStateFlow<List<DonutSlice>>(emptyList())
    private val _face2Slices = MutableStateFlow<List<DonutSlice>>(emptyList())

    private val _expandedGroupIndex = MutableStateFlow(-1)
    val expandedGroupIndex: StateFlow<Int> = _expandedGroupIndex.asStateFlow()

    private val _hoveredSubIndex = MutableStateFlow(-1)
    val hoveredSubIndex: StateFlow<Int> = _hoveredSubIndex.asStateFlow()

    private val _currentSlices = MutableStateFlow<List<DonutSlice>>(emptyList())
    val currentSlices: StateFlow<List<DonutSlice>> = _currentSlices.asStateFlow()

    private val _hoveredSliceIndex = MutableStateFlow(-1)
    val hoveredSliceIndex: StateFlow<Int> = _hoveredSliceIndex.asStateFlow()

    private val _flipTrigger = MutableStateFlow(0)
    val flipTrigger: StateFlow<Int> = _flipTrigger.asStateFlow()

    private val _blobTone = MutableStateFlow(BlobTone.Idle)
    val blobTone: StateFlow<BlobTone> = _blobTone.asStateFlow()

    private val _pendingRenameSlice = MutableStateFlow<DonutSlice?>(null)
    val pendingRenameSlice: StateFlow<DonutSlice?> = _pendingRenameSlice.asStateFlow()

    private val _uiState = MutableStateFlow<ConversionUiState>(ConversionUiState.Ready)
    val uiState: StateFlow<ConversionUiState> = _uiState.asStateFlow()

    var isSharedSession: Boolean = false

    private var conversionJob: Job? = null

    fun loadFromUri(context: Context, uri: Uri, isShare: Boolean = false, intentMimeType: String? = null) {
        loadFromUris(context, listOf(uri), isShare, intentMimeType)
    }

    fun loadFromUris(context: Context, uris: List<Uri>, isShare: Boolean = false, intentMimeType: String? = null) {
        if (uris.isEmpty()) return
        isSharedSession = isShare
        currentFiles = uris.distinct().map { StorageHelper.resolveSharedFile(context, it, intentMimeType) }
        _currentFileCount.value = currentFiles.size
        val file = currentFiles.first()
        _currentFile.value = file

        val (face1, face2) = EngineRegistry.getSlicesForSource(file.extension)
        _face1Slices.value = face1
        _face2Slices.value = face2

        _faceIndex.value = 0
        _currentSlices.value = if (face1.isNotEmpty()) face1 else face2
        _hoveredSliceIndex.value = -1
        _blobTone.value = BlobTone.Idle
        _uiState.value = ConversionUiState.Ready
    }

    fun flipWheel() {
        collapseGroup()
        val f1 = _face1Slices.value
        val f2 = _face2Slices.value
        if (f1.isEmpty() && f2.isEmpty()) return

        val nextFace = if (_faceIndex.value == 0) 1 else 0
        _faceIndex.value = nextFace
        _currentSlices.value = if (nextFace == 0) {
            if (f1.isNotEmpty()) f1 else f2
        } else {
            if (f2.isNotEmpty()) f2 else f1
        }
        _hoveredSliceIndex.value = -1
        _flipTrigger.value += 1
    }

    fun expandGroup(index: Int) {
        val slices = _currentSlices.value
        if (index in slices.indices && slices[index].isGroup && slices[index].subSlices.isNotEmpty()) {
            if (_expandedGroupIndex.value != index) {
                _expandedGroupIndex.value = index
                _hoveredSubIndex.value = -1
                HootSoundManager.playFan()
            }
        }
    }

    fun collapseGroup() {
        if (_expandedGroupIndex.value >= 0) {
            _expandedGroupIndex.value = -1
            _hoveredSubIndex.value = -1
        }
    }

    fun toggleGroup(index: Int) {
        if (_expandedGroupIndex.value == index) {
            collapseGroup()
        } else {
            expandGroup(index)
        }
    }

    fun setHoveredSub(subIndex: Int) {
        if (_hoveredSubIndex.value != subIndex) {
            _hoveredSubIndex.value = subIndex
            if (subIndex >= 0) {
                HootSoundManager.playTick(subIndex)
            }
        }
    }

    fun setHoveredSlice(index: Int) {
        _hoveredSliceIndex.value = index
        _blobTone.value = if (index >= 0) BlobTone.Alert else BlobTone.Idle
    }

    fun onDropOverSubSlice(groupIndex: Int, subIndex: Int) {
        val slices = _currentSlices.value
        if (groupIndex in slices.indices) {
            val group = slices[groupIndex]
            if (subIndex in group.subSlices.indices) {
                val subSlice = group.subSlices[subIndex]
                collapseGroup()
                _pendingRenameSlice.value = subSlice
                _blobTone.value = BlobTone.Anticipation
            }
        }
    }

    fun onDropOverSlice(index: Int) {
        val slices = _currentSlices.value
        if (index in slices.indices) {
            val slice = slices[index]
            if (slice.isGroup && slice.subSlices.isNotEmpty()) {
                toggleGroup(index)
                return
            }
            collapseGroup()
            _pendingRenameSlice.value = slice
            _blobTone.value = BlobTone.Anticipation
        } else {
            _hoveredSliceIndex.value = -1
            _blobTone.value = BlobTone.Idle
        }
    }

    fun dismissRenameDialog() {
        _pendingRenameSlice.value = null
        _hoveredSliceIndex.value = -1
        _blobTone.value = BlobTone.Idle
    }

    fun startConversion(
        context: Context,
        finalFileName: String,
        targetFormat: String,
        resizePercent: Int? = null,
        targetWidth: Int? = null,
        targetMaxMb: Int? = null,
        rotationDegrees: Int? = null,
        flipHorizontal: Boolean = false,
        flipVertical: Boolean = false,
        cropAspectRatio: Float? = null,
        removeBackground: Boolean = false
    ) {
        val file = _currentFile.value ?: return
        _pendingRenameSlice.value = null
        _blobTone.value = BlobTone.Busy
        _uiState.value = ConversionUiState.Converting(targetFormat, 0f)

        conversionJob?.cancel()
        conversionJob = viewModelScope.launch {
            try {
                val engine = EngineRegistry.findEngine(file.extension, targetFormat)
                    ?: throw IllegalStateException("No engine available for ${file.extension} -> $targetFormat")

                if (targetFormat.equals("extract", ignoreCase = true) && engine is app.hoot.engine.NativeZipEngine) {
                    val extraction = engine.extractArchiveBesideSource(
                        context = context,
                        zipSource = file,
                        folderName = finalFileName.substringBeforeLast('.', finalFileName),
                        onProgress = { p -> _uiState.value = ConversionUiState.Converting(targetFormat, p) },
                        isCancelled = { !coroutineContext[Job]!!.isActive }
                    )
                    completeSuccess(
                        context = context,
                        outputUri = extraction.firstUri,
                        outputName = "${extraction.fileCount} files extracted",
                        isBesideOriginal = extraction.isBesideOriginal,
                        destinationPath = extraction.destinationPath
                    )
                    return@launch
                }

                val targetExt = if (targetFormat.lowercase(Locale.ROOT) == "same") file.extension else targetFormat
                val destination = StorageHelper.createOutputDestination(
                    context = context,
                    sourceFile = file,
                    desiredFileName = finalFileName,
                    targetExtension = targetExt
                )

                destination.outputStream.use { outStream ->
                    when {
                        currentFiles.size > 1 && targetFormat.equals("zip", ignoreCase = true) && engine is app.hoot.engine.NativeZipEngine -> {
                            engine.packMultipleFilesToZip(context, currentFiles, outStream,
                                onProgress = { p -> _uiState.value = ConversionUiState.Converting(targetFormat, p) },
                                isCancelled = { !coroutineContext[Job]!!.isActive })
                        }
                        currentFiles.size > 1 && targetFormat.equals("pdf", ignoreCase = true) && engine is app.hoot.engine.NativePdfEngine -> {
                            engine.convertMultipleImagesToPdf(context, currentFiles, outStream,
                                onProgress = { p -> _uiState.value = ConversionUiState.Converting(targetFormat, p) },
                                isCancelled = { !coroutineContext[Job]!!.isActive })
                        }
                        currentFiles.size > 1 && currentFiles.all { it.extension.equals(file.extension, ignoreCase = true) } &&
                                targetFormat.equals(file.extension, ignoreCase = true) &&
                                resizePercent == null && targetWidth == null && targetMaxMb == null && rotationDegrees == null &&
                                !flipHorizontal && !flipVertical && cropAspectRatio == null && !removeBackground && engine is app.hoot.engine.NativeImageEngine -> {
                            engine.mergeImages(context, currentFiles, outStream, targetFormat,
                                onProgress = { p -> _uiState.value = ConversionUiState.Converting(targetFormat, p) },
                                isCancelled = { !coroutineContext[Job]!!.isActive })
                        }
                        else -> engine.convert(
                        context = context,
                        source = file,
                        outputStream = outStream,
                        targetFormat = targetFormat,
                        onProgress = { p ->
                            _uiState.value = ConversionUiState.Converting(targetFormat, p)
                        },
                        isCancelled = { !coroutineContext[Job]!!.isActive },
                        resizePercent = resizePercent,
                        targetWidth = targetWidth,
                        targetMaxMb = targetMaxMb,
                        rotationDegrees = rotationDegrees,
                        flipHorizontal = flipHorizontal,
                        flipVertical = flipVertical,
                        cropAspectRatio = cropAspectRatio,
                        removeBackground = removeBackground
                        )
                    }
                    outStream.flush()
                }

                // Ensure MediaScanner finishes indexing the new file in the exact folder
                val pathToScan = destination.absolutePath ?: destination.displayPath
                val scannedUri = StorageHelper.scanFileSynchronously(context, pathToScan)
                val finalOutputUri = scannedUri ?: destination.uri

                completeSuccess(
                    context = context,
                    outputUri = finalOutputUri,
                    outputName = finalFileName,
                    isBesideOriginal = destination.isBesideOriginal,
                    destinationPath = destination.displayPath
                )
            } catch (e: Exception) {
                android.util.Log.e("Hoot", "Conversion failed", e)
                if (coroutineContext[Job]!!.isActive) {
                    _blobTone.value = BlobTone.Idle
                    _uiState.value = ConversionUiState.Error(e.message ?: "Conversion failed")
                }
            }
        }
    }

    fun cancelConversion() {
        conversionJob?.cancel()
        _currentFile.value = null
        currentFiles = emptyList()
        _currentFileCount.value = 0
        collapseGroup()
        _pendingRenameSlice.value = null
        _hoveredSliceIndex.value = -1
        _blobTone.value = BlobTone.Idle
        _uiState.value = ConversionUiState.Ready
    }

    private suspend fun completeSuccess(
        context: Context,
        outputUri: Uri,
        outputName: String,
        isBesideOriginal: Boolean,
        destinationPath: String
    ) {
        HootSoundManager.playSuccess()
        _blobTone.value = BlobTone.Celebration
        _uiState.value = ConversionUiState.Success(outputUri, outputName, isBesideOriginal, destinationPath)
        delay(1200)
        val activity = (context as? Activity) ?: (context as? ContextWrapper)?.let { wrapper ->
            var current: Context? = wrapper
            while (current is ContextWrapper) {
                if (current is Activity) return@let current
                current = current.baseContext
            }
            null
        }
        activity?.setResult(Activity.RESULT_OK, Intent().apply {
            data = outputUri
            putExtra(Intent.EXTRA_STREAM, outputUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
        activity?.finish()
        _blobTone.value = BlobTone.Idle
        _hoveredSliceIndex.value = -1
        _uiState.value = ConversionUiState.Ready
    }
}
