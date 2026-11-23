package io.github.joshitup.lantern.update

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import java.io.File
import java.security.MessageDigest

/** What a downloaded APK claims to be. Certificates are SHA-256 digests in lowercase hex. */
data class ArchiveFacts(val packageName: String, val versionCode: Long, val certSha256: Set<String>)

object ApkVerifier {
    /**
     * Pure rule: same package, strictly higher versionCode, and the archive's signers exactly match [expectedCerts].
     * An empty [expectedCerts] (nothing to compare against) never passes.
     */
    fun verify(archive: ArchiveFacts?, installedPackage: String, installedCode: Long, expectedCerts: Set<String>): Boolean {
        if (archive == null || expectedCerts.isEmpty()) return false
        return archive.packageName == installedPackage &&
            archive.versionCode > installedCode &&
            archive.certSha256 == expectedCerts
    }

    /** The build's pinned certificate when there is one, else the installed app's own signers. */
    fun expectedCerts(buildConfigSha256: String, installed: Set<String>): Set<String> =
        if (buildConfigSha256.isNotEmpty()) setOf(buildConfigSha256) else installed

    /** Reads the archive's package, version and signers; null when Android can't parse it. */
    fun facts(context: Context, apk: File): ArchiveFacts? {
        val info = context.packageManager.getPackageArchiveInfo(apk.path, signingFlags()) ?: return null
        return ArchiveFacts(info.packageName, info.longVersionCode, digests(info.signingInfo))
    }

    /** The installed app's current signers. */
    fun installedCerts(context: Context): Set<String> =
        digests(context.packageManager.getPackageInfo(context.packageName, signingFlags()).signingInfo)

    /** The full check of a downloaded file against this install. Android glue around [verify]. */
    fun check(context: Context, apk: File, installedCode: Long, buildConfigSha256: String): Boolean {
        val installed = if (buildConfigSha256.isEmpty()) installedCerts(context) else emptySet()
        return verify(facts(context, apk), context.packageName, installedCode, expectedCerts(buildConfigSha256, installed))
    }

    private fun signingFlags() = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())

    // apkContentsSigners: the current signer(s), without certificates rotated away from.
    private fun digests(info: SigningInfo?): Set<String> =
        info?.apkContentsSigners.orEmpty().map(::sha256).toSet()

    private fun sha256(sig: Signature): String =
        MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
}
