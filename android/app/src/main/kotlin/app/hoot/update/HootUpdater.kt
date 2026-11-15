package app.hoot.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object HootUpdater {
    private const val PREFS = "hoot_update_state"
    private const val KEY_AUTOMATIC = "automatic_updates"
    private const val KEY_AVAILABLE = "available_android_release"
    private const val KEY_PENDING_APPROVAL = "pending_android_approval"
    private const val KEY_CONFIRMATION_INTENT = "pending_android_confirmation_intent"
    private const val PERIODIC_WORK = "hoot-android-update-check"
    private const val CHECK_WORK = "hoot-android-update-check-once"

    fun initialize(context: Context) {
        val app = context.applicationContext
        if (automaticUpdatesEnabled(app)) {
            schedulePeriodicCheck(app)
            if (app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("last_check_ms", 0L) == 0L) {
                checkNow(app)
            }
        }
    }

    fun automaticUpdatesEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTOMATIC, true)

    fun setAutomaticUpdatesEnabled(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTOMATIC, enabled).apply()
        if (enabled) {
            schedulePeriodicCheck(app)
            checkNow(app)
        } else {
            WorkManager.getInstance(app).cancelUniqueWork(PERIODIC_WORK)
            WorkManager.getInstance(app).cancelUniqueWork(CHECK_WORK)
            WorkManager.getInstance(app).cancelAllWorkByTag(INSTALL_WORK_TAG)
        }
    }

    fun checkNow(context: Context, manual: Boolean = false) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
            .setConstraints(constraints)
            .setInputData(androidx.work.workDataOf(UpdateCheckWorker.INPUT_MANUAL to manual))
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(CHECK_WORK, ExistingWorkPolicy.KEEP, request)
    }

    fun enqueueInstall(context: Context, release: AndroidRelease) {
        saveAvailable(context, release)
        val request = OneTimeWorkRequestBuilder<UpdateInstallWorker>()
            .setInputData(androidx.work.workDataOf(UpdateInstallWorker.INPUT_TAG to release.tag))
            .addTag(INSTALL_WORK_TAG)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork("hoot-update-install-${release.versionCode}", ExistingWorkPolicy.KEEP, request)
    }

    fun saveAvailable(context: Context, release: AndroidRelease?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_AVAILABLE, release?.toJson())
            .apply()
    }

    fun availableRelease(context: Context): AndroidRelease? = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_AVAILABLE, null)?.let(AndroidRelease::fromJson)
    } catch (_: Exception) { null }

    fun markPendingApproval(context: Context, tag: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PENDING_APPROVAL, tag).apply()
    }

    fun saveConfirmationIntent(context: Context, intent: android.content.Intent?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CONFIRMATION_INTENT, intent?.toUri(0)).apply()
    }

    fun pendingConfirmationIntent(context: Context): android.content.Intent? = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CONFIRMATION_INTENT, null)
            ?.let { android.content.Intent.parseUri(it, 0) }
    } catch (_: Exception) { null }

    fun pendingApproval(context: Context): AndroidRelease? {
        val tag = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PENDING_APPROVAL, null) ?: return null
        return availableRelease(context)?.takeIf { it.tag == tag }
    }

    fun clearPendingApproval(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_PENDING_APPROVAL).remove(KEY_CONFIRMATION_INTENT).apply()
    }

    fun markChecked(context: Context, message: String? = null) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("last_check_ms", System.currentTimeMillis())
            .putString("last_check_message", message)
            .apply()
    }

    fun lastCheckMessage(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last_check_message", null)

    private fun schedulePeriodicCheck(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(6, TimeUnit.HOURS, 30, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    const val INSTALL_WORK_TAG = "hoot-android-update-install"
}
