package io.github.joshitup.lantern.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.joshitup.lantern.App

/** The install session's result. Not exported: only our own PendingIntent reaches it. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val session = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        when (status) {
            // First update after an adb install, or whenever Android insists: show its confirmation.
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) {
                    controller(context).reportInstallFailure(session, "no confirmation from the system")
                    return
                }
                // Kept for the UI, which launches it itself while the app is in the foreground.
                controller(context).awaitConfirmation(session, confirm)
                // One launcher at a time: in the background only (where it may still be blocked; the UI retries on resume).
                if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
                try {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: RuntimeException) {
                    Log.w(TAG, "confirmation launch failed: ${e::class.java.simpleName}")
                    controller(context).reportInstallFailure(session, "couldn't show the confirmation")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // this process is being replaced
            else -> controller(context).reportInstallFailure(session, 
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status",
            )
        }
    }

    private fun controller(context: Context) = (context.applicationContext as App).container.updateController

    private companion object {
        const val TAG = "InstallResult"
    }
}
