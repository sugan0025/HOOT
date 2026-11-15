package app.hoot

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import app.hoot.audio.HootSoundManager
import app.hoot.ui.main.HootMainScreen
import app.hoot.ui.main.HootViewModel
import app.hoot.ui.theme.HootTheme

class MainActivity : ComponentActivity() {

    private val viewModel: HootViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        enableEdgeToEdge()

        HootSoundManager.init(this)

        handleIntent(intent)

        setContent {
            HootTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HootMainScreen(viewModel = viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            HootSoundManager.playFlip()
            HootSoundManager.playFan()
            viewModel.flipWheel()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        HootSoundManager.release()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                } ?: intent.data ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri

                if (uri != null) {
                    dispatchIntentActions(listOf(uri), intent)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                var uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                }
                if (uris.isNullOrEmpty() && intent.clipData != null) {
                    val clip = intent.clipData!!
                    uris = ArrayList((0 until clip.itemCount).mapNotNull { clip.getItemAt(it)?.uri })
                }
                if (!uris.isNullOrEmpty()) {
                    dispatchIntentActions(uris, intent)
                }
            }
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
                if (uri != null) {
                    dispatchIntentActions(listOf(uri), intent)
                }
            }
        }
    }

    private fun dispatchIntentActions(uris: List<Uri>, intent: Intent) {
        viewModel.loadFromUris(this, uris, isShare = true, intentMimeType = intent.type)
        val targetFormat = intent.getStringExtra("app.hoot.extra.TARGET_FORMAT")
        if (!targetFormat.isNullOrBlank()) {
            val baseName = intent.getStringExtra("app.hoot.extra.TARGET_NAME")
                ?: "converted_${System.currentTimeMillis()}.$targetFormat"
            val removeBg = intent.getBooleanExtra("app.hoot.extra.REMOVE_BACKGROUND", false) ||
                           targetFormat.contains("nobg", ignoreCase = true) ||
                           baseName.contains("nobg", ignoreCase = true)
            val rotation = if (intent.hasExtra("app.hoot.extra.ROTATION")) intent.getIntExtra("app.hoot.extra.ROTATION", 0) else null
            val resizePercent = if (intent.hasExtra("app.hoot.extra.RESIZE_PERCENT")) intent.getIntExtra("app.hoot.extra.RESIZE_PERCENT", 0) else null
            val targetWidth = if (intent.hasExtra("app.hoot.extra.TARGET_WIDTH")) intent.getIntExtra("app.hoot.extra.TARGET_WIDTH", 0) else null
            val targetMaxMb = if (intent.hasExtra("app.hoot.extra.TARGET_MAX_MB")) intent.getIntExtra("app.hoot.extra.TARGET_MAX_MB", 0) else null
            val cropAspect = if (intent.hasExtra("app.hoot.extra.CROP_ASPECT")) intent.getFloatExtra("app.hoot.extra.CROP_ASPECT", 1f) else null

            viewModel.startConversion(
                context = this,
                finalFileName = baseName,
                targetFormat = targetFormat,
                resizePercent = resizePercent,
                targetWidth = targetWidth,
                targetMaxMb = targetMaxMb,
                rotationDegrees = rotation,
                cropAspectRatio = cropAspect,
                removeBackground = removeBg
            )
        }
        val expandGroup = intent.getIntExtra("app.hoot.extra.EXPAND_GROUP", -1)
        if (expandGroup >= 0) {
            viewModel.expandGroup(expandGroup)
        }
        if (intent.getBooleanExtra("app.hoot.extra.FLIP_WHEEL", false)) {
            viewModel.flipWheel()
        }
    }
}
