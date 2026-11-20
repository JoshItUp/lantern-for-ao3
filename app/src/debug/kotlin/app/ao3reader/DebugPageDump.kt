package app.ao3reader

import android.content.Context
import okhttp3.HttpUrl
import java.io.File

fun pageDump(context: Context): PageDump = DebugPageDump(File(context.filesDir, "debug-pages"))

/** Writes each fetched page to [dir], keeping the newest [keep] files. */
class DebugPageDump(private val dir: File, private val keep: Int = 30) : PageDump {
    private var seq = System.currentTimeMillis()

    @Synchronized
    override fun dump(url: HttpUrl, html: String) {
        dir.mkdirs()
        val raw = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
        val safe = raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        File(dir, "%015d_%s.html".format(seq++, safe)).writeText(html)
        dir.listFiles()?.sortedBy { it.name }?.dropLast(keep)?.forEach { it.delete() }
    }
}
