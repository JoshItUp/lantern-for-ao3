package io.github.joshitup.lantern.ui.update

import android.content.Intent
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import io.github.joshitup.lantern.update.UpdateState

/**
 * The update sheet, the launch "What's new" and the install hand-offs that need the activity: the
 * confirmation the receiver couldn't show, and the retry once "Install unknown apps" was granted.
 */
@Composable
internal fun UpdateHost(updates: UpdateViewModel, permissionIntent: () -> Intent) {
    val state by updates.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifeState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = lifeState.isAtLeast(Lifecycle.State.RESUMED)

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { updates.onResume() }

    // The only launcher while the app is in the foreground (the receiver launches only in the background):
    // on entering AwaitingConfirmation while resumed, and again on every resume while it lasts.
    val install = state.install
    LaunchedEffect(install, resumed) {
        if (!resumed || install !is UpdateState.AwaitingConfirmation) return@LaunchedEffect
        try {
            context.startActivity(install.intent)
        } catch (e: RuntimeException) {
            Log.w("UpdateHost", "confirmation launch failed: ${e::class.java.simpleName}")
        }
        updates.confirmationLaunched() // the controller's watchdog fails it if nothing comes back
    }

    // After the first frame: startup never waits on DataStore or the notes fetch.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        updates.loadWhatsNew()
    }

    val release = state.release
    if (state.sheetOpen && release != null) {
        UpdateSheet(
            release = release,
            install = install,
            onLater = updates::later,
            onDownload = updates::download,
            onAllowInstall = {
                try {
                    context.startActivity(permissionIntent())
                } catch (e: RuntimeException) {
                    Log.w("UpdateHost", "permission screen failed: ${e::class.java.simpleName}")
                }
            },
            onDismiss = updates::closeSheet,
        )
    } else {
        state.whatsNew?.let { WhatsNewSheet(it, onDismiss = updates::whatsNewShown) }
    }
}
