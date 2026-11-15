package app.hoot.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings

/** Small trampoline opened only after the user taps an update notification. */
class UpdateInstallActivity : Activity() {
    private var waitingForInstallPermission = false
    private var tag: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tag = intent.getStringExtra(EXTRA_TAG)
        continueInstallIfAllowed()
    }

    override fun onResume() {
        super.onResume()
        if (waitingForInstallPermission) {
            waitingForInstallPermission = false
            if (canInstallPackages()) enqueueInstall()
            finish()
        }
    }

    private fun continueInstallIfAllowed() {
        if (Build.VERSION.SDK_INT >= 26 && !canInstallPackages()) {
            waitingForInstallPermission = true
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        } else {
            enqueueInstall()
            finish()
        }
    }

    private fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()

    private fun enqueueInstall() {
        val release = HootUpdater.availableRelease(this)?.takeIf { it.tag == tag } ?: return
        HootUpdater.enqueueInstall(this, release)
    }

    companion object { const val EXTRA_TAG = "app.hoot.update.TAG" }
}
