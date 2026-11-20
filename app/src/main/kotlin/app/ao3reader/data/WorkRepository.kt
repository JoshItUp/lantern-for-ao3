package app.ao3reader.data

import app.ao3reader.ao3.model.ChapterContent
import app.ao3reader.ao3.model.ChapterRef
import app.ao3reader.ao3.net.FetchResult
import app.ao3reader.ao3.net.Fetcher
import app.ao3reader.ao3.parse.Ao3Urls
import app.ao3reader.ao3.parse.ChapterParser
import app.ao3reader.ao3.parse.NavigateParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** What the reader needs from [WorkRepository]; an interface so the ViewModel can be tested with a fake. */
interface ChapterSource {
    suspend fun chapters(workId: Long): Loaded<List<ChapterRef>>
    suspend fun chapter(workId: Long, ref: ChapterRef): Loaded<ChapterContent>
    fun prefetch(workId: Long, ref: ChapterRef)
}

/** An Ok response that has no chapter text (e.g. AO3's sign-in page for a registered-users-only work). */
class NotAChapterPageException : IllegalStateException("not a chapter page")

class WorkRepository(
    private val fetcher: Fetcher,
    private val cache: ChapterCache,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ChapterSource {
    private val chapterLists = object : LinkedHashMap<Long, List<ChapterRef>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<ChapterRef>>?) = size > MAX_MEMOIZED
    }
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override suspend fun chapters(workId: Long): Loaded<List<ChapterRef>> {
        synchronized(chapterLists) { chapterLists[workId] }?.let { return Loaded.Ok(it) }
        return when (val r = fetcher.get(Ao3Urls.navigate(workId))) {
            is FetchResult.Ok -> {
                val list = withContext(parseDispatcher) { NavigateParser.parse(r.html) }
                synchronized(chapterLists) { chapterLists[workId] = list }
                Loaded.Ok(list)
            }
            else -> Loaded.Error(r)
        }
    }

    override suspend fun chapter(workId: Long, ref: ChapterRef): Loaded<ChapterContent> {
        val key = keyOf(ref)
        val cached = withContext(ioDispatcher) { cache.get(workId, key) }
        if (cached != null) {
            val parsed = parseValid(cached, workId)
            if (parsed != null) return Loaded.Ok(parsed)
            withContext(ioDispatcher) { cache.remove(workId, key) }
        }
        val r = fetcher.get(Ao3Urls.chapter(workId, ref.chapterId))
        if (r !is FetchResult.Ok) return Loaded.Error(r)
        val parsed = parseValid(r.html, workId)
            ?: return Loaded.Error(FetchResult.Failed(NotAChapterPageException()))
        withContext(ioDispatcher) { try { cache.put(workId, key, r.html) } catch (_: IOException) {} }
        return Loaded.Ok(parsed)
    }

    /** Parses [html]; null if it is not a chapter page, has an empty body, or the parser throws. */
    private suspend fun parseValid(html: String, workId: Long): ChapterContent? =
        withContext(parseDispatcher) {
            try {
                if (!ChapterParser.looksLikeChapterPage(html)) null
                else ChapterParser.parse(html, workId).takeIf { it.body.isNotEmpty() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

    override fun prefetch(workId: Long, ref: ChapterRef) {
        val key = keyOf(ref)
        val flightKey = "$workId/$key"
        if (!inFlight.add(flightKey)) return
        try {
            scope.launch {
                try {
                    if (withContext(ioDispatcher) { cache.get(workId, key) } != null) return@launch
                    val r = fetcher.get(Ao3Urls.chapter(workId, ref.chapterId))
                    if (r is FetchResult.Ok && withContext(parseDispatcher) { ChapterParser.looksLikeChapterPage(r.html) }) withContext(ioDispatcher) { cache.put(workId, key, r.html) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                } finally {
                    inFlight.remove(flightKey)
                }
            }
        } catch (e: Throwable) {
            inFlight.remove(flightKey)
            if (e is CancellationException) throw e
        }
    }

    private fun keyOf(ref: ChapterRef) = ref.chapterId?.toString() ?: "single"

    private companion object { const val MAX_MEMOIZED = 20 }
}
