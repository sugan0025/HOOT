package app.hoot.engine

import android.content.Context
import android.net.Uri
import android.os.Environment
import app.hoot.model.SharedFile
import app.hoot.storage.StorageHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class NativeZipEngine : ConversionEngine {
    override val id = "native_zip"
    override val displayName = "Android Zip Engine"

    companion object {
        const val MAX_ZIP_ENTRIES = 10_000
        const val MAX_UNCOMPRESSED_SIZE_BYTES = 500L * 1024 * 1024 // 500 MB safety limit

        fun sanitizeEntryName(name: String): String {
            return name.replace('\\', '/').trimStart('/')
        }

        fun isPathSafe(targetDirectory: File, entryName: String): Boolean {
            val canonicalTargetDirPath = targetDirectory.canonicalPath
            val newFile = File(targetDirectory, entryName)
            val canonicalNewFilePath = newFile.canonicalPath

            // Zip-Slip Path Traversal Protection:
            // Must reside strictly within targetDirectory
            return (canonicalNewFilePath.startsWith(canonicalTargetDirPath + File.separator) ||
                    canonicalNewFilePath == canonicalTargetDirPath)
        }
    }

    data class ExtractionResult(val firstUri: Uri, val destinationPath: String, val fileCount: Int, val isBesideOriginal: Boolean)

    override fun canConvert(sourceExtension: String, targetFormat: String): Boolean {
        val src = sourceExtension.lowercase(Locale.ROOT)
        val tgt = targetFormat.lowercase(Locale.ROOT)
        return (tgt == "zip" && src != "zip") || (src == "zip" && tgt == "extract")
    }

    override fun getSupportedTargets(sourceExtension: String): List<String> {
        val src = sourceExtension.lowercase(Locale.ROOT)
        return when (src) {
            "zip" -> listOf("extract")
            else -> listOf("zip")
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
        packMultipleFilesToZip(context, listOf(source), outputStream, onProgress, isCancelled)
    }

    suspend fun packMultipleFilesToZip(
        context: Context,
        sources: List<SharedFile>,
        outputStream: OutputStream,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ) = withContext(Dispatchers.IO) {
        onProgress(0.1f)
        if (isCancelled()) return@withContext

        val zipOut = ZipOutputStream(outputStream)
        val totalFiles = sources.size
        val buffer = ByteArray(32 * 1024)

        try {
            sources.forEachIndexed { index, source ->
                if (isCancelled()) return@withContext

                val entryName = sanitizeEntryName(source.displayName)
                val zipEntry = ZipEntry(entryName)
                zipOut.putNextEntry(zipEntry)

                context.contentResolver.openInputStream(source.uri)?.use { inStream ->
                    var read: Int
                    while (inStream.read(buffer).also { read = it } != -1) {
                        if (isCancelled()) return@withContext
                        zipOut.write(buffer, 0, read)
                    }
                }
                zipOut.closeEntry()

                val progress = 0.1f + 0.85f * ((index + 1).toFloat() / totalFiles)
                onProgress(progress)
            }
            zipOut.finish()
            onProgress(1.0f)
        } finally {
            // Caller handles closing destination output stream
        }
    }

    suspend fun extractZipSafely(
        context: Context,
        zipSource: SharedFile,
        targetDirectory: File,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): List<File> = withContext(Dispatchers.IO) {
        val extractedFiles = mutableListOf<File>()
        var totalBytesExtracted = 0L
        var entryCount = 0

        context.contentResolver.openInputStream(zipSource.uri)?.use { inStream ->
            ZipInputStream(inStream).use { zipIn ->
                var entry: ZipEntry? = zipIn.nextEntry
                val buffer = ByteArray(32 * 1024)

                while (entry != null) {
                    if (isCancelled()) return@withContext extractedFiles

                    entryCount++
                    if (entryCount > MAX_ZIP_ENTRIES) {
                        throw SecurityException("ZIP contains too many entries (max $MAX_ZIP_ENTRIES)")
                    }

                    val sanitizedName = sanitizeEntryName(entry.name)

                    // Zip-Slip Path Traversal Protection
                    if (!isPathSafe(targetDirectory, sanitizedName)) {
                        throw SecurityException("Zip entry is outside of target directory: ${entry.name}")
                    }

                    val newFile = File(targetDirectory, sanitizedName)

                    if (entry.isDirectory) {
                        if (!newFile.isDirectory && !newFile.mkdirs()) {
                            throw IllegalStateException("Failed to create directory $newFile")
                        }
                    } else {
                        val parent = newFile.parentFile
                        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                            throw IllegalStateException("Failed to create parent directory $parent")
                        }

                        FileOutputStream(newFile).use { outStream ->
                            var read: Int
                            while (zipIn.read(buffer).also { read = it } != -1) {
                                if (isCancelled()) return@withContext extractedFiles
                                outStream.write(buffer, 0, read)
                                totalBytesExtracted += read

                                if (totalBytesExtracted > MAX_UNCOMPRESSED_SIZE_BYTES) {
                                    throw SecurityException("Exceeded maximum uncompressed archive size ($MAX_UNCOMPRESSED_SIZE_BYTES bytes)")
                                }
                            }
                        }
                        extractedFiles.add(newFile)
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
        }
        onProgress(1.0f)
        extractedFiles
    }

    suspend fun extractArchiveBesideSource(
        context: Context,
        zipSource: SharedFile,
        folderName: String,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): ExtractionResult = withContext(Dispatchers.IO) {
        val safeFolder = folderName.trim().replace(Regex("[^A-Za-z0-9._ -]"), "_").trim('.', ' ')
            .ifBlank { "Extracted" } + "_extracted"
        val root = File.createTempFile("hoot_unzip_", "", context.cacheDir)
        root.delete()
        check(root.mkdirs()) { "Could not prepare temporary extraction directory" }
        try {
            val files = extractZipSafely(context, zipSource, root, onProgress, isCancelled)
            if (isCancelled()) throw CancellationException("Extraction cancelled")
            if (files.isEmpty()) throw IllegalStateException("The ZIP archive contains no files")

            val parentPath = zipSource.relativePath?.trim('/')?.takeIf { it.isNotEmpty() }
                ?: Environment.DIRECTORY_DOWNLOADS
            val outputRelativePath = "$parentPath/$safeFolder"
            var firstUri: Uri? = null
            files.forEachIndexed { index, extracted ->
                if (isCancelled()) throw CancellationException("Extraction cancelled")
                val flattenedName = extracted.relativeTo(root).path.replace('\\', '/').replace('/', '_')
                val ext = flattenedName.substringAfterLast('.', "").lowercase(Locale.ROOT)
                val destination = StorageHelper.createOutputDestination(
                    context = context,
                    sourceFile = zipSource,
                    desiredFileName = flattenedName,
                    targetExtension = ext,
                    relativePathOverride = outputRelativePath
                )
                destination.outputStream.use { out -> extracted.inputStream().use { it.copyTo(out) } }
                if (firstUri == null) {
                    val scannedUri = StorageHelper.scanFileSynchronously(
                        context,
                        destination.absolutePath ?: destination.displayPath
                    )
                    firstUri = scannedUri ?: destination.uri
                }
                onProgress(0.55f + 0.45f * (index + 1) / files.size)
            }
            ExtractionResult(firstUri!!, outputRelativePath, files.size,
                zipSource.relativePath != null || zipSource.filePath != null)
        } finally {
            root.deleteRecursively()
        }
    }
}
