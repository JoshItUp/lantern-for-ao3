package app.ao3reader.ui.common

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import app.ao3reader.data.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Ask for POST_NOTIFICATIONS only on Android 13+ (API 33), only when it isn't granted, and only if it was never asked. */
fun shouldAskNotificationPermission(sdk: Int, granted: Boolean, askedBefore: Boolean): Boolean =
    sdk >= 33 && !granted && !askedBefore

/**
 * Returns a call for a Download, Update or Retry tap: the first such tap asks for the notification
 * permission (so the "Downloading" notification can show), at most once ever ([SettingsStore]).
 * It never gates the download: the caller requests it whatever the answer.
 */
@Composable
fun rememberNotificationPermissionAsk(settings: SettingsStore): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The answer doesn't matter: downloads run either way, the notification just won't show.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return remember(settings, context, launcher) {
        var checking = false   // a second quick tap must not ask twice before the flag is written
        val ask: () -> Unit = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !checking) {
                checking = true
                scope.launch {
                    try {
                        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                            PackageManager.PERMISSION_GRANTED
                        if (shouldAskNotificationPermission(Build.VERSION.SDK_INT, granted, settings.notificationPermissionAsked.first())) {
                            settings.markNotificationPermissionAsked()
                            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    } finally {
                        checking = false
                    }
                }
            }
        }
        ask
    }
}
