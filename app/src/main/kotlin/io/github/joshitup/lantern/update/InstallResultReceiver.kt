package io.github.joshitup.lantern.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import io.github.joshitup.lantern.App

/** The install session's result. Not exported: only our own PendingIntent reaches it. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            // First update after an adb install, or whenever Android insists: show its confirmation.
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) {
                    controller(context).reportInstallFailure("no confirmation from the system")
                    return
                }
                try {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: RuntimeException) {
                    Log.w(TAG, "confirmation launch failed: ${e::class.java.simpleName}")
                    controller(context).reportInstallFailure("couldn't show the confirmation")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // this process is being replaced
            else -> controller(context).reportInstallFailure(
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status",
            )
        }
    }

    private fun controller(context: Context) = (context.applicationContext as App).container.updateController

    private companion object {
        const val TAG = "InstallResult"
    }
}
