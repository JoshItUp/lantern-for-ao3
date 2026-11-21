package app.ao3reader.data

import java.io.File

/**
 * Bounded LRU disk cache of raw chapter HTML at `dir/{workId}/{chapterKey}.html`.
 * Recency is the file's lastModified, set from [clock]. Blocking and thread-safe.
 *
 * Content cache only: never read forms or tokens from here (a cached signed-in page holds an old
 * authenticity token and another moment's account state). Cleared on an explicit sign-out.
 */
class ChapterCache(
    private val dir: File,
    private val maxBytes: Long = 64L shl 20,
    private val maxAgeMs: Long = 30L * 24 * 3600 * 1000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()

    private fun file(workId: Long, chapterKey: String): File {
        require(chapterKey == "single" || (chapterKey.isNotEmpty() && chapterKey.all { it in '0'..'9' })) {
            "Invalid chapterKey: $chapterKey"
        }
        return File(File(dir, workId.toString()), "$chapterKey.html")
    }

    fun get(workId: Long, chapterKey: String): String? = synchronized(lock) {
        val f = file(workId, chapterKey)
        if (!f.isFile) return null
        if (clock() - f.lastModified() > maxAgeMs) {
            f.delete()
            return null
        }
        val text = try { f.readText() } catch (e: java.io.IOException) { return null }
        f.setLastModified(clock())
        text
    }

    fun put(workId: Long, chapterKey: String, html: String) {
        synchronized(lock) {
            val f = file(workId, chapterKey)
            val parent = f.parentFile!!
            parent.mkdirs()
            val tmp = File(parent, "$chapterKey.html.tmp")
            tmp.writeText(html)
            tmp.setLastModified(clock())
            if (!tmp.renameTo(f)) {
                f.delete()
                if (!tmp.renameTo(f)) { tmp.delete(); return }
            }
            f.setLastModified(clock())
            trim()
        }
    }

    /** Deletes every cached chapter (explicit sign-out: cached signed-in pages belong to that account). */
    fun clear() {
        synchronized(lock) { dir.listFiles()?.forEach { it.deleteRecursively() } }
    }

    fun remove(workId: Long, chapterKey: String) {
        synchronized(lock) { file(workId, chapterKey).delete() }
    }

    fun trim() {
        synchronized(lock) {
            val now = clock()
            val dirs = dir.listFiles { d -> d.isDirectory } ?: return
            val live = ArrayList<File>()
            for (d in dirs) {
                d.listFiles()?.forEach { f ->
                    if (f.name.endsWith(".tmp") || now - f.lastModified() > maxAgeMs) f.delete() else live.add(f)
                }
            }
            var total = live.sumOf { it.length() }
            if (total > maxBytes) {
                for (f in live.sortedBy { it.lastModified() }) {
                    if (total <= maxBytes) break
                    total -= f.length()
                    f.delete()
                }
            }
            dirs.forEach { d -> if (d.list()?.isEmpty() == true) d.delete() }
        }
    }
}
