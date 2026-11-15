package app.hoot.storage

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import app.hoot.model.SharedFile
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object StorageHelper {

    fun resolveSharedFile(context: Context, uri: Uri, intentMimeType: String? = null): SharedFile {
        val resolver = context.contentResolver
        var displayName = "shared_file"
        var size = 0L
        var mimeType = resolver.getType(uri) ?: intentMimeType ?: "application/octet-stream"
        if (mimeType == "application/octet-stream" && !intentMimeType.isNullOrBlank() && intentMimeType != "*/*") {
            mimeType = intentMimeType
        }
        var relativePath: String? = null
        var filePath: String? = null

        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            // 1. Query safe OpenableColumns without crashing on permission errors
            try {
                val safeProjection = arrayOf(
                    OpenableColumns.DISPLAY_NAME,
                    OpenableColumns.SIZE
                )
                resolver.query(uri, safeProjection, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIdx != -1 && !cursor.isNull(nameIdx)) displayName = cursor.getString(nameIdx)
                        if (sizeIdx != -1 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                    }
                }
            } catch (_: Exception) {}

            // 2. Isolate RELATIVE_PATH and DATA probe in separate try/catch
            try {
                val mediaProjection = arrayOf(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.DATA
                )
                resolver.query(uri, mediaProjection, null, null, null)?.use { cursor ->
                    val relIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                    val dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (cursor.moveToFirst()) {
                        if (relIdx != -1 && !cursor.isNull(relIdx)) relativePath = cursor.getString(relIdx)
                        if (dataIdx != -1 && !cursor.isNull(dataIdx)) filePath = cursor.getString(dataIdx)
                    }
                }
            } catch (_: Exception) {}
        } else if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val path = uri.path
            if (path != null) {
                val file = File(path)
                if (file.exists()) {
                    displayName = file.name
                    size = file.length()
                    filePath = file.absolutePath
                }
            }
        }

        // 1. Inspect encoded path in URI (Xiaomi File Manager, Gallery, FileProviders)
        if (filePath == null) {
            val rawPath = Uri.decode(uri.path ?: "")
            when {
                rawPath.contains("/storage/emulated/0/") -> {
                    val candidate = "/storage/emulated/0/" + rawPath.substringAfter("/storage/emulated/0/")
                    if (File(candidate).exists()) filePath = candidate
                }
                rawPath.contains("/external_files/") -> {
                    val rel = rawPath.substringAfter("/external_files/")
                    val candidate = File(Environment.getExternalStorageDirectory(), rel)
                    if (candidate.exists()) filePath = candidate.absolutePath
                }
                rawPath.contains("/external/") -> {
                    val rel = rawPath.substringAfter("/external/")
                    val candidate = File(Environment.getExternalStorageDirectory(), rel)
                    if (candidate.exists()) filePath = candidate.absolutePath
                }
                rawPath.contains("/root/") -> {
                    val candidate = "/" + rawPath.substringAfter("/root/")
                    if (File(candidate).exists()) filePath = candidate
                }
            }
        }

        // 2. DocumentsContract (Storage Access Framework)
        if (filePath == null && uri.authority == "com.android.externalstorage.documents") {
            try {
                val docId = DocumentsContract.getDocumentId(uri)
                if (docId.startsWith("primary:")) {
                    val rel = docId.removePrefix("primary:")
                    val candidate = File(Environment.getExternalStorageDirectory(), rel)
                    if (candidate.exists()) filePath = candidate.absolutePath
                    if (rel.contains('/')) {
                        relativePath = rel.substringBeforeLast('/') + "/"
                    }
                }
            } catch (_: Exception) {}
        }

        // 3. Fallback query to MediaStore by displayName & size to find original folder
        if (filePath == null && relativePath == null && displayName.isNotEmpty() && displayName != "shared_file") {
            try {
                val queryUri = MediaStore.Files.getContentUri("external")
                val proj = arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.RELATIVE_PATH)
                val selection = if (size > 0L) {
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.SIZE} = ?"
                } else {
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
                }
                val selArgs = if (size > 0L) arrayOf(displayName, size.toString()) else arrayOf(displayName)
                resolver.query(queryUri, proj, selection, selArgs, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val dIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                        val rIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                        if (dIdx != -1 && !cursor.isNull(dIdx)) filePath = cursor.getString(dIdx)
                        if (rIdx != -1 && !cursor.isNull(rIdx)) relativePath = cursor.getString(rIdx)
                    }
                }
            } catch (_: Exception) {}
        }

        // 4. If relativePath wasn't resolved but filePath is available under /storage/emulated/0/
        if (relativePath == null && filePath != null) {
            val externalRoot = Environment.getExternalStorageDirectory().absolutePath
            if (filePath.startsWith(externalRoot)) {
                val rel = filePath.removePrefix(externalRoot).trimStart(File.separatorChar)
                if (rel.contains(File.separatorChar)) {
                    relativePath = rel.substringBeforeLast(File.separatorChar) + "/"
                }
            }
        }

        var extension = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension.isEmpty() && mimeType != "application/octet-stream" && mimeType != "*/*") {
            val deducedExt = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
                ?: when (mimeType.lowercase(Locale.ROOT)) {
                    "image/png" -> "png"
                    "image/jpeg", "image/jpg" -> "jpg"
                    "image/webp" -> "webp"
                    "application/pdf" -> "pdf"
                    "application/zip" -> "zip"
                    else -> null
                }
            if (deducedExt != null) {
                extension = deducedExt.lowercase(Locale.ROOT)
                if (!displayName.contains('.')) {
                    displayName = "$displayName.$extension"
                }
            }
        }
        if (mimeType == "application/octet-stream" && extension.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)?.let {
                mimeType = it
            }
        }

        android.util.Log.d("Hoot", "Resolved file: name=$displayName, ext=$extension, mime=$mimeType, path=$filePath, rel=$relativePath")

        return SharedFile(
            uri = uri,
            displayName = displayName,
            size = size,
            mimeType = mimeType,
            extension = extension,
            relativePath = relativePath,
            filePath = filePath
        )
    }

    fun openInputStream(context: Context, source: SharedFile): java.io.InputStream? {
        try {
            val stream = context.contentResolver.openInputStream(source.uri)
            if (stream != null) return stream
        } catch (e: Exception) {
            android.util.Log.d("Hoot", "openInputStream contentResolver failed: ${e.message}")
        }

        val directPath = when {
            source.uri.scheme == ContentResolver.SCHEME_FILE -> source.uri.path
            source.filePath != null -> source.filePath
            else -> null
        }
        if (directPath != null) {
            try {
                val f = File(directPath)
                if (f.exists() && f.canRead()) {
                    return java.io.FileInputStream(f)
                }
            } catch (e: Exception) {
                android.util.Log.d("Hoot", "openInputStream FileInputStream failed: ${e.message}")
            }

            // Fallback: Query MediaStore for this file if contentResolver/direct access failed
            try {
                val projection = arrayOf(MediaStore.Images.Media._ID)
                val selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ? OR ${MediaStore.Images.Media.DATA} = ?"
                val selectionArgs = arrayOf(source.displayName, directPath)
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(0)
                        val contentUri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                        val stream = context.contentResolver.openInputStream(contentUri)
                        if (stream != null) return stream
                    }
                }
            } catch (e: Exception) {
                android.util.Log.d("Hoot", "openInputStream MediaStore query failed: ${e.message}")
            }
        }
        return null
    }

    data class OutputDestination(
        val uri: Uri,
        val outputStream: OutputStream,
        val isBesideOriginal: Boolean,
        val displayPath: String,
        val absolutePath: String? = null
    )

    fun createOutputDestination(
        context: Context,
        sourceFile: SharedFile,
        desiredFileName: String,
        targetExtension: String,
        relativePathOverride: String? = null
    ): OutputDestination {
        val resolver = context.contentResolver
        val ext = targetExtension.lowercase(Locale.ROOT).trimStart('.')
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: when (ext) {
                "pdf" -> "application/pdf"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "webp" -> "image/webp"
                "zip" -> "application/zip"
                "txt" -> "text/plain"
                else -> "application/octet-stream"
            }

        // 1. Try direct file parent directory if directly writable
        val directPath = when {
            sourceFile.uri.scheme == ContentResolver.SCHEME_FILE -> sourceFile.uri.path
            sourceFile.filePath != null -> sourceFile.filePath
            else -> null
        }
        if (directPath != null) {
            val sourceFileObj = File(directPath)
            val parentDir = sourceFileObj.parentFile
            if (parentDir != null && parentDir.exists() && parentDir.canWrite()) {
                try {
                    val destinationDir = relativePathOverride?.let { File(parentDir, it.substringAfterLast('/')) } ?: parentDir
                    if (!destinationDir.exists() && !destinationDir.mkdirs()) throw IllegalStateException("Cannot create output folder")
                    val targetFile = getUniqueFile(destinationDir, desiredFileName)
                    val stream = FileOutputStream(targetFile)
                    return OutputDestination(
                        uri = Uri.fromFile(targetFile),
                        outputStream = SyncedFileOutputStream(context, targetFile, stream, mimeType),
                        isBesideOriginal = true,
                        displayPath = targetFile.absolutePath,
                        absolutePath = targetFile.absolutePath
                    )
                } catch (_: Exception) {}
            }
        }

        // 2. MediaStore destination: save beside original in exact relativePath
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val isImageTarget = ext in listOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "avif")
            val isAudioTarget = ext in listOf("mp3", "wav", "m4a", "aac")
            val isVideoTarget = ext in listOf("mp4", "mkv")

            // Determine target collection & relative directory
            val (collectionUri, targetRelativeDir) = when {
                !relativePathOverride.isNullOrBlank() ->
                    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to relativePathOverride.trim('/')
                !sourceFile.relativePath.isNullOrEmpty() -> {
                    val rawRel = sourceFile.relativePath.trim('/')
                    when {
                        // Original was in DCIM or Pictures
                        rawRel.startsWith("DCIM", ignoreCase = true) || rawRel.startsWith("Pictures", ignoreCase = true) -> {
                            if (isImageTarget) {
                                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to rawRel
                            } else if (isVideoTarget) {
                                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to rawRel
                            } else {
                                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to rawRel
                            }
                        }
                        // Original was in Download or subfolder of Download
                        rawRel.startsWith("Download", ignoreCase = true) -> {
                            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to rawRel
                        }
                        // Original was in Music or Podcasts
                        rawRel.startsWith("Music", ignoreCase = true) || rawRel.startsWith("Podcasts", ignoreCase = true) -> {
                            if (isAudioTarget) {
                                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to rawRel
                            } else {
                                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to rawRel
                            }
                        }
                        // Original was in Movies
                        rawRel.startsWith("Movies", ignoreCase = true) -> {
                            if (isVideoTarget) {
                                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to rawRel
                            } else {
                                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to rawRel
                            }
                        }
                        // Any other folder (Documents, etc.)
                        else -> {
                            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to rawRel
                        }
                    }
                }
                // Fallbacks if relativePath is unknown
                isImageTarget -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_PICTURES
                isAudioTarget -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MUSIC
                isVideoTarget -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MOVIES
                else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_DOWNLOADS
            }

            val isBeside = !sourceFile.relativePath.isNullOrEmpty() || !relativePathOverride.isNullOrBlank()

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, desiredFileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$targetRelativeDir/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val insertedUri = resolver.insert(collectionUri, contentValues)
            if (insertedUri != null) {
                val stream = resolver.openOutputStream(insertedUri)
                if (stream != null) {
                    var resolvedAbsolutePath: String? = null
                    try {
                        resolver.query(insertedUri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                                if (idx != -1 && !cursor.isNull(idx)) {
                                    resolvedAbsolutePath = cursor.getString(idx)
                                }
                            }
                        }
                    } catch (_: Exception) {}

                    val absolutePath = resolvedAbsolutePath
                        ?: File(Environment.getExternalStorageDirectory(), "$targetRelativeDir/$desiredFileName").absolutePath

                    return OutputDestination(
                        uri = insertedUri,
                        outputStream = PendingMediaStoreOutputStream(context, insertedUri, stream, absolutePath, mimeType),
                        isBesideOriginal = isBeside,
                        displayPath = "$targetRelativeDir/$desiredFileName",
                        absolutePath = absolutePath
                    )
                }
            }
        }

        // 3. Fallback: App External Files Directory
        val fallbackDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "Converted")
        if (!fallbackDir.exists()) fallbackDir.mkdirs()
        val targetFile = getUniqueFile(fallbackDir, desiredFileName)
        val stream = FileOutputStream(targetFile)
        return OutputDestination(
            uri = Uri.fromFile(targetFile),
            outputStream = SyncedFileOutputStream(context, targetFile, stream, mimeType),
            isBesideOriginal = false,
            displayPath = targetFile.absolutePath,
            absolutePath = targetFile.absolutePath
        )
    }

    private fun getUniqueFile(dir: File, fileName: String): File {
        var file = File(dir, fileName)
        if (!file.exists()) return file

        val baseName = fileName.substringBeforeLast('.')
        val extension = fileName.substringAfterLast('.', "")
        val extSuffix = if (extension.isNotEmpty()) ".$extension" else ""

        var index = 1
        while (file.exists()) {
            file = File(dir, "$baseName ($index)$extSuffix")
            index++
        }
        return file
    }

    private class SyncedFileOutputStream(
        private val context: Context,
        private val targetFile: File,
        private val delegate: FileOutputStream,
        private val mimeType: String
    ) : OutputStream() {
        override fun write(b: Int) = delegate.write(b)
        override fun write(b: ByteArray) = delegate.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)
        override fun flush() {
            delegate.flush()
            try {
                delegate.fd.sync()
            } catch (_: Exception) {}
        }

        override fun close() {
            try {
                flush()
            } catch (_: Exception) {}
            delegate.close()

            // Synchronously scan with MediaScanner so Android Gallery / File Manager immediately lists the new file
            scanFileSynchronously(context, targetFile.absolutePath, mimeType)
        }
    }

    private class PendingMediaStoreOutputStream(
        private val context: Context,
        private val uri: Uri,
        private val delegate: OutputStream,
        private val absolutePath: String,
        private val mimeType: String
    ) : OutputStream() {
        override fun write(b: Int) = delegate.write(b)
        override fun write(b: ByteArray) = delegate.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)
        override fun flush() {
            delegate.flush()
            try {
                (delegate as? FileOutputStream)?.fd?.sync()
            } catch (_: Exception) {}
        }

        override fun close() {
            try {
                flush()
            } catch (_: Exception) {}
            try {
                delegate.close()
            } catch (_: Exception) {}

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, values, null, null)
            }

            // Retrieve confirmed post-commit filesystem path from DATA column
            var finalPath = absolutePath
            try {
                context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                        if (idx != -1 && !cursor.isNull(idx)) {
                            val data = cursor.getString(idx)
                            if (!data.isNullOrBlank()) {
                                finalPath = data
                            }
                        }
                    }
                }
            } catch (_: Exception) {}

            // Synchronously scan with MediaScanner so Android Gallery / File Manager immediately lists the new file
            scanFileSynchronously(context, finalPath, mimeType)

        }
    }

    fun scanFileSynchronously(context: Context, path: String, mimeType: String? = null): Uri? {
        val absPath = if (path.startsWith("/")) {
            path
        } else {
            File(Environment.getExternalStorageDirectory(), path).absolutePath
        }
        val latch = CountDownLatch(1)
        var scannedUri: Uri? = null
        try {
            MediaScannerConnection.scanFile(
                context.applicationContext,
                arrayOf(absPath),
                if (mimeType != null) arrayOf(mimeType) else null
            ) { _, uri ->
                scannedUri = uri
                latch.countDown()
            }
            latch.await(2500, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            latch.countDown()
        }
        return scannedUri
    }
}
