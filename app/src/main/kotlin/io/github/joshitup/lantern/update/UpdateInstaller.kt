package io.github.joshitup.lantern.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import java.io.File

/** Installs a verified APK in place with a [PackageInstaller] session; silent once Lantern is the installer of record. */
class UpdateInstaller(private val context: Context) {

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens "Install unknown apps" for this package. */
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Blocking (writes the file into the session); the result arrives at [InstallResultReceiver]. [onSession] gets the id before commit. */
    fun install(apk: File, onSession: (Int) -> Unit) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        onSession(id)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("lantern.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName)
                // Mutable: the installer adds the status extras to this intent.
                val pending = PendingIntent.getBroadcast(
                    context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            installer.abandonSession(id)
            throw e
        }
    }
}
