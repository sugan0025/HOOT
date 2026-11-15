package app.hoot.update

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.delay

@Composable
fun HootUpdateSettingsDialog(
    onDismiss: () -> Unit,
    onRequestNotifications: () -> Unit
) {
    val context = LocalContext.current
    var automatic by remember { mutableStateOf(HootUpdater.automaticUpdatesEnabled(context)) }
    var refresh by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            refresh++
            delay(1200)
        }
    }
    val release = remember(refresh) { HootUpdater.availableRelease(context) }
    val approvalPending = remember(refresh) { HootUpdater.pendingApproval(context) != null }
    val message = remember(refresh) { HootUpdater.lastCheckMessage(context) }
    val notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    val currentVersion = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "Local build"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hoot settings", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 470.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Android Hoot · version $currentVersion", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Automatic updates", fontWeight = FontWeight.SemiBold)
                        Text("Check about every six hours, then download and install when Android allows it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = automatic,
                        onCheckedChange = {
                            automatic = it
                            HootUpdater.setAutomaticUpdatesEnabled(context, it)
                        }
                    )
                }
                if (Build.VERSION.SDK_INT >= 33 && !notificationsEnabled) {
                    OutlinedButton(onClick = onRequestNotifications, modifier = Modifier.fillMaxWidth()) {
                        Text("Enable update notifications")
                    }
                    Text("If notifications stay off, Hoot will show an approval prompt next time it opens.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (release != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Version ${release.versionName} is available", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        if (release.releaseNotes.isNotBlank()) Text(release.releaseNotes, style = MaterialTheme.typography.bodySmall)
                        if (approvalPending) Text("Android is waiting for your approval to finish installing.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                        Button(
                            onClick = {
                                if (approvalPending) HootUpdater.clearPendingApproval(context)
                                HootUpdater.enqueueInstall(context, release)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (approvalPending) "Review update" else "Update now") }
                    }
                }
                if (message != null) Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(2.dp))
                TextButton(
                    onClick = { HootUpdater.checkNow(context, manual = true) },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) { Text("Check for updates") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        containerColor = MaterialTheme.colorScheme.surface
    )
}
