package app.hoot.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.hoot.R

object UpdateNotifications {
    private const val CHANNEL_ID = "hoot_updates"
    private const val NOTIFICATION_ID = 4811

    fun notifyApprovalRequired(context: Context, release: AndroidRelease, systemIntent: Intent? = null) {
        HootUpdater.markPendingApproval(context, release.tag)
        HootUpdater.saveConfirmationIntent(context, systemIntent)
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        ensureChannel(context)
        val intent = systemIntent ?: Intent(context, UpdateInstallActivity::class.java).putExtra(UpdateInstallActivity.EXTRA_TAG, release.tag)
        val content = PendingIntent.getActivity(
            context, NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val later = Intent(context, UpdateActionReceiver::class.java)
            .setAction(UpdateActionReceiver.ACTION_LATER)
        val laterPending = PendingIntent.getBroadcast(
            context, NOTIFICATION_ID + 1, later,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Hoot is ready to update")
            .setContentText("Android needs your approval to install version ${release.versionName}.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(release.releaseNotes.ifBlank { "Review the Android installer to finish updating Hoot." }))
            .setContentIntent(content)
            .addAction(0, "Review update", content)
            .addAction(0, "Later", laterPending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try { manager.notify(NOTIFICATION_ID, notification) } catch (_: SecurityException) { }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Hoot updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Hoot Android update availability and installation approval"
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}

class UpdateInstallStatusReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val tag = intent.getStringExtra(UpdateInstallWorker.STATUS_TAG) ?: return
        val release = HootUpdater.availableRelease(context)?.takeIf { it.tag == tag } ?: return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_SUCCESS -> {
                HootUpdater.clearPendingApproval(context)
                HootUpdater.saveAvailable(context, null)
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                UpdateNotifications.notifyApprovalRequired(context, release, confirmation)
            }
            else -> {
                HootUpdater.markPendingApproval(context, tag)
                UpdateNotifications.notifyApprovalRequired(context, release)
            }
        }
    }
}

class UpdateActionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_LATER) {
            NotificationManagerCompat.from(context).cancel(4811)
        }
    }

    companion object { const val ACTION_LATER = "app.hoot.action.UPDATE_LATER" }
}
