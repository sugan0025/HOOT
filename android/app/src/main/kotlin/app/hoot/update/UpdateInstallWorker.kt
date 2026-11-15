package app.hoot.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

class UpdateInstallWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val tag = inputData.getString(INPUT_TAG) ?: return Result.failure()
        val release = HootUpdater.availableRelease(applicationContext)?.takeIf { it.tag == tag } ?: return Result.failure()
        return try {
            val installedCode = installedVersionCode()
            if (release.versionCode <= installedCode) return Result.success()
            val apk = downloadAndVerify(release)
            if (Build.VERSION.SDK_INT >= 26 && !applicationContext.packageManager.canRequestPackageInstalls()) {
                UpdateNotifications.notifyApprovalRequired(applicationContext, release)
                return Result.success()
            }
            install(apk, release)
            Result.success()
        } catch (e: ApkValidationException) {
            HootUpdater.markChecked(applicationContext, e.message ?: "Downloaded update did not pass verification")
            Result.failure(workDataOf("error" to (e.message ?: "APK validation failed")))
        } catch (_: Exception) {
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
        }
    }

    private fun downloadAndVerify(release: AndroidRelease): File {
        val target = File(applicationContext.filesDir, "hoot-update-${release.versionCode}.apk")
        val partial = File(applicationContext.filesDir, "hoot-update-${release.versionCode}.apk.part")
        if (target.exists() && target.length() == release.apkSizeBytes) {
            verifyApk(target, release)
            return target
        }
        val available = StatFs(applicationContext.filesDir.absolutePath).availableBytes
        require(available > release.apkSizeBytes + STORAGE_RESERVE_BYTES) { "Not enough storage to download the Hoot update" }

        if (partial.exists() && partial.length() == release.apkSizeBytes) {
            verifyApk(partial, release)
            if (!partial.renameTo(target)) throw IllegalStateException("Could not finalize the staged update")
            return target
        }
        val offset = if (partial.exists()) partial.length() else 0L
        val connection = URL(release.apkUrl).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "HOOT-Android-Updater")
            if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
            val responseCode = connection.responseCode
            val final = connection.url.toURI()
            if (!final.scheme.equals("https", true) || final.host !in ALLOWED_DOWNLOAD_HOSTS) {
                throw ApkValidationException("APK download left the trusted HTTPS hosts")
            }
            val append = offset > 0 && responseCode == HttpURLConnection.HTTP_PARTIAL
            if (!append && responseCode !in 200..299) throw IllegalStateException("APK download returned HTTP $responseCode")
            val start = if (append) offset else 0L
            connection.inputStream.use { input ->
                FileOutputStream(partial, append).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = start
                    while (true) {
                        if (isStopped) throw InterruptedException("Update download stopped")
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        setProgressAsync(workDataOf(PROGRESS_BYTES to downloaded, TOTAL_BYTES to release.apkSizeBytes))
                        if (downloaded > release.apkSizeBytes) throw ApkValidationException("Downloaded APK exceeds its declared size")
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        if (partial.length() != release.apkSizeBytes) throw IllegalStateException("APK download was incomplete")
        verifyApk(partial, release)
        if (target.exists() && !target.delete()) throw IllegalStateException("Could not replace the staged update")
        if (!partial.renameTo(target)) throw IllegalStateException("Could not finalize the staged update")
        return target
    }

    private fun verifyApk(apk: File, release: AndroidRelease) {
        if (sha256(apk) != release.sha256) throw ApkValidationException("APK SHA-256 verification failed")
        val archiveInfo = packageInfo(apk.absolutePath) ?: throw ApkValidationException("Downloaded file is not a valid Android APK")
        if (archiveInfo.packageName != applicationContext.packageName) throw ApkValidationException("APK package name does not match Hoot")
        val archiveVersion = if (Build.VERSION.SDK_INT >= 28) archiveInfo.longVersionCode else @Suppress("DEPRECATION") archiveInfo.versionCode.toLong()
        if (archiveVersion != release.versionCode) throw ApkValidationException("APK version code does not match update metadata")
        val currentCert = currentSignerFingerprint() ?: throw ApkValidationException("Could not verify installed Hoot signing identity")
        if (!currentCert.equals(release.signerSha256, true)) throw ApkValidationException("Update metadata signing identity differs from installed Hoot")
        val downloadedCert = signerFingerprint(archiveInfo)
        if (downloadedCert == null || !downloadedCert.equals(currentCert, true)) {
            throw ApkValidationException("Downloaded APK is not signed with the installed Hoot key")
        }
    }

    private fun packageInfo(path: String) = if (Build.VERSION.SDK_INT >= 33) {
        applicationContext.packageManager.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
    } else {
        @Suppress("DEPRECATION") applicationContext.packageManager.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
    }

    private fun currentSignerFingerprint(): String? {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            applicationContext.packageManager.getPackageInfo(applicationContext.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION") applicationContext.packageManager.getPackageInfo(applicationContext.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }
        return signerFingerprint(info)
    }

    private fun signerFingerprint(info: android.content.pm.PackageInfo): String? {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION") info.signatures
        }
        if (signatures.isNullOrEmpty() || signatures.size != 1) return null
        return MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray())
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    private fun installedVersionCode(): Long {
        val info = applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    private fun install(apk: File, release: AndroidRelease) {
        val installer = applicationContext.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(applicationContext.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("hoot-update.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val callback = Intent(applicationContext, UpdateInstallStatusReceiver::class.java)
                .putExtra(STATUS_TAG, release.tag)
            val pending = android.app.PendingIntent.getBroadcast(
                applicationContext, sessionId, callback,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            session.commit(pending.intentSender)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    class ApkValidationException(message: String) : Exception(message)

    companion object {
        const val INPUT_TAG = "android_release_tag"
        const val STATUS_TAG = "android_release_tag"
        const val PROGRESS_BYTES = "downloaded_bytes"
        const val TOTAL_BYTES = "total_bytes"
        private const val MAX_RETRIES = 5
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 45_000
        private const val STORAGE_RESERVE_BYTES = 4L * 1024 * 1024
        private val ALLOWED_DOWNLOAD_HOSTS = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    }
}
