package io.github.joshitup.lantern.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Downloads a release APK into [dir] (cacheDir/updates), keeping only that one file. */
class UpdateDownloader(
    private val client: UpdateClient,
    private val dir: File,
    /** Off only in tests (MockWebServer is plain http). */
    private val requireHttps: Boolean = true,
) {

    /** Writes `lantern-X.Y.Z.apk.part`, checks its size against [ReleaseInfo.apkSize], then renames it. Throws [IOException]. */
    suspend fun download(release: ReleaseInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("can't create the update dir")
        dir.listFiles()?.forEach { it.delete() } // older APKs and leftover parts
        val target = File(dir, "lantern-${release.version}.apk")
        val part = File(dir, "${target.name}.part")
        try {
            client.download(release.apkUrl).use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                // The final URL after redirects: never accept a downgrade to plain http.
                if (requireHttps && !resp.request.url.isHttps) throw IOException("not https")
                val body = resp.body
                val total = body.contentLength().takeIf { it > 0 } ?: release.apkSize
                var written = 0L
                body.byteStream().use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                            if (total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                if (release.apkSize > 0 && written != release.apkSize) {
                    throw IOException("size mismatch: $written of ${release.apkSize} bytes")
                }
            }
            if (!part.renameTo(target)) throw IOException("rename failed")
            onProgress(1f)
            target
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
    }
}
