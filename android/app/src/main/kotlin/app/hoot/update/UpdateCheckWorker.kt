package app.hoot.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val manual = inputData.getBoolean(INPUT_MANUAL, false)
        val automatic = HootUpdater.automaticUpdatesEnabled(applicationContext)
        if (!automatic && !manual) return Result.success()
        return try {
            val release = GitHubReleaseCatalog().fetchLatestAndroidRelease()
            HootUpdater.markChecked(applicationContext)
            if (release == null || release.versionCode <= installedVersionCode()) {
                HootUpdater.saveAvailable(applicationContext, null)
                HootUpdater.markChecked(applicationContext, "Hoot is up to date.")
                return Result.success()
            }
            HootUpdater.saveAvailable(applicationContext, release)
            HootUpdater.markChecked(applicationContext, "Version ${release.versionName} is available.")
            if (automatic) HootUpdater.enqueueInstall(applicationContext, release)
            Result.success()
        } catch (e: Exception) {
            HootUpdater.markChecked(applicationContext, e.message ?: "Update check failed")
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
        }
    }

    private fun installedVersionCode(): Long {
        val info = applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0)
        return if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    companion object {
        const val INPUT_MANUAL = "manual_update_check"
        private const val MAX_RETRIES = 5
    }
}
