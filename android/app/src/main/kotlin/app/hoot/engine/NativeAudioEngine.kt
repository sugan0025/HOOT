package app.hoot.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import app.hoot.model.SharedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.Locale

class NativeAudioEngine : ConversionEngine {
    override val id = "native_audio"
    override val displayName = "Android Media Engine"

    companion object {
        val SUPPORTED_VIDEO_SOURCES = setOf("mp4", "mkv", "mov", "webm", "3gp", "avi")
        val SUPPORTED_AUDIO_SOURCES = setOf("m4a", "aac")

        /**
         * Only advertise conversions this packet-copying engine can produce without decoding.
         */
        fun isLosslessDemux(sourceExtension: String, targetFormat: String): Boolean {
            val src = sourceExtension.lowercase(Locale.ROOT)
            val tgt = targetFormat.lowercase(Locale.ROOT)
            return when {
                src in SUPPORTED_VIDEO_SOURCES -> tgt == "m4a"
                src == "m4a" -> tgt == "aac"
                src == "aac" -> tgt == "m4a"
                else -> false
            }
        }
    }

    override fun canConvert(sourceExtension: String, targetFormat: String): Boolean {
        return isLosslessDemux(sourceExtension, targetFormat)
    }

    override fun getSupportedTargets(sourceExtension: String): List<String> {
        val src = sourceExtension.lowercase(Locale.ROOT)
        return when {
            SUPPORTED_VIDEO_SOURCES.contains(src) -> listOf("m4a")
            src == "m4a" -> listOf("aac")
            src == "aac" -> listOf("m4a")
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
        onProgress(0.1f)
        if (isCancelled()) return@withContext

        // Create temporary local scratch file for MediaExtractor/MediaMuxer
        val tempInput = File.createTempFile("hoot_audio_in_", ".${source.extension}", context.cacheDir)
        val target = targetFormat.lowercase(Locale.ROOT)
        val tempOutput = File.createTempFile("hoot_audio_out_", if (target == "aac") ".aac" else ".m4a", context.cacheDir)

        try {
            // Copy source stream to local temp file
            context.contentResolver.openInputStream(source.uri)?.use { inStream ->
                tempInput.outputStream().use { outStream ->
                    inStream.copyTo(outStream)
                }
            }

            onProgress(0.25f)
            if (isCancelled()) return@withContext

            extractAudioTrack(tempInput, tempOutput, target, onProgress, isCancelled)

            onProgress(0.9f)
            if (isCancelled()) return@withContext

            // Stream extracted audio to destination outputStream
            FileInputStream(tempOutput).use { inStream ->
                inStream.copyTo(outputStream)
            }
            outputStream.flush()
            onProgress(1.0f)
        } finally {
            tempInput.delete()
            tempOutput.delete()
        }
    }

    private fun extractAudioTrack(
        inputFile: File,
        outputFile: File,
        targetFormat: String,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ) {
        val extractor = MediaExtractor()
        extractor.setDataSource(inputFile.absolutePath)

        var audioTrackIndex = -1
        var audioFormat: MediaFormat? = null

        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                audioFormat = format
                break
            }
        }

        if (audioTrackIndex == -1 || audioFormat == null) {
            extractor.release()
            throw IllegalStateException("No audio track found in media file: ${inputFile.name}")
        }

        val mime = audioFormat.getString(MediaFormat.KEY_MIME).orEmpty()
        if (mime != "audio/mp4a-latm") {
            extractor.release()
            throw UnsupportedOperationException("Only AAC audio tracks can be exported as M4A or AAC without quality loss")
        }

        extractor.selectTrack(audioTrackIndex)

        if (targetFormat == "aac") {
            try {
                writeAdtsAac(extractor, audioFormat, outputFile, onProgress, isCancelled)
            } finally {
                extractor.release()
            }
            return
        }

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val muxerTrackIndex = muxer.addTrack(audioFormat)
        muxer.start()

        val maxBufferSize = if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        } else {
            1024 * 1024
        }
        val buffer = ByteBuffer.allocate(maxBufferSize)
        val bufferInfo = MediaCodec.BufferInfo()

        val durationUs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
            audioFormat.getLong(MediaFormat.KEY_DURATION)
        } else {
            1L
        }

        try {
            while (true) {
                if (isCancelled()) break

                bufferInfo.offset = 0
                bufferInfo.size = extractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) {
                    bufferInfo.size = 0
                    break
                }

                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }

                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                extractor.advance()

                if (durationUs > 0) {
                    val progress = 0.25f + 0.65f * (bufferInfo.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f)
                    onProgress(progress)
                }
            }
        } finally {
            try {
                muxer.stop()
                muxer.release()
            } catch (_: Exception) {}
            extractor.release()
        }
    }

    private fun writeAdtsAac(
        extractor: MediaExtractor,
        format: MediaFormat,
        outputFile: File,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ) {
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val frequencyIndex = listOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
            .indexOf(sampleRate)
        require(frequencyIndex >= 0 && channelCount in 1..7) { "Unsupported AAC sample rate or channel count" }
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        val buffer = ByteBuffer.allocate(if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 1024 * 1024)
        FileOutputStream(outputFile).use { out ->
            while (!isCancelled()) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val frameLength = size + 7
                require(frameLength <= 8191) { "AAC frame exceeds the ADTS frame limit" }
                val header = byteArrayOf(
                    0xFF.toByte(), 0xF1.toByte(),
                    (((1 shl 6) or (frequencyIndex shl 2) or (channelCount shr 2)) and 0xFF).toByte(),
                    ((((channelCount and 3) shl 6) or (frameLength shr 11)) and 0xFF).toByte(),
                    ((frameLength shr 3) and 0xFF).toByte(),
                    ((((frameLength and 7) shl 5) or 0x1F) and 0xFF).toByte(),
                    0xFC.toByte()
                )
                out.write(header)
                val data = ByteArray(size)
                buffer.position(0)
                buffer.get(data, 0, size)
                out.write(data)
                if (durationUs > 0) onProgress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                extractor.advance()
            }
        }
        if (!isCancelled() && outputFile.length() <= 7L) throw IllegalStateException("The source contains no AAC frames")
    }
}
