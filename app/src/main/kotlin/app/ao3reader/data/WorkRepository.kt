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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private val clock: () -> Long = System::currentTimeMillis,
) : ChapterSource {
    private class Memo(val list: List<ChapterRef>, val fetchedAt: Long)

    private val chapterLists = object : LinkedHashMap<Long, Memo>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Memo>?) = size > MAX_MEMOIZED
    }
    /** Prefetches running now, by "workId/key"; [chapter] waits for one rather than fetching the same page twice. */
    private val inFlight = ConcurrentHashMap<String, Job>()

    /**
     * Memoized for [CHAPTER_LIST_TTL_MS], so a work in progress picks up new chapters. An expired list
     * is refetched; if that fails (or finds no chapters) the expired list is served instead of an error.
     */
    override suspend fun chapters(workId: Long): Loaded<List<ChapterRef>> {
        val memo = synchronized(chapterLists) { chapterLists[workId] }
        if (memo != null && clock() - memo.fetchedAt < CHAPTER_LIST_TTL_MS) return Loaded.Ok(memo.list)
        return when (val r = fetcher.get(Ao3Urls.navigate(workId))) {
            is FetchResult.Ok -> {
                val list = withContext(parseDispatcher) { NavigateParser.parse(r.html) }
                if (list.isEmpty() && memo != null) return Loaded.Ok(memo.list)
                synchronized(chapterLists) { chapterLists[workId] = Memo(list, clock()) }
                Loaded.Ok(list)
            }
            else -> if (memo != null) Loaded.Ok(memo.list) else Loaded.Error(r)
        }
    }

    override suspend fun chapter(workId: Long, ref: ChapterRef): Loaded<ChapterContent> {
        val key = keyOf(ref)
        // A prefetch of this chapter is under way (a swipe soon after opening): let it land in the cache.
        inFlight[flightKey(workId, key)]?.join()
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

    /** Parses [html] once; null if it is not a chapter page, has an empty body, or the parser throws. */
    private suspend fun parseValid(html: String, workId: Long): ChapterContent? =
        withContext(parseDispatcher) {
            try {
                ChapterParser.parseOrNull(html, workId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

    override fun prefetch(workId: Long, ref: ChapterRef) {
        val key = keyOf(ref)
        val flightKey = flightKey(workId, key)
        if (inFlight.containsKey(flightKey)) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (withContext(ioDispatcher) { cache.get(workId, key) } != null) return@launch
                // Background work: a challenge is left for the next reader load to solve.
                val r = fetcher.get(Ao3Urls.chapter(workId, ref.chapterId), interactive = false)
                if (r is FetchResult.Ok && withContext(parseDispatcher) { ChapterParser.looksLikeChapterPage(r.html) }) {
                    withContext(ioDispatcher) { cache.put(workId, key, r.html) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            }
        }
        if (inFlight.putIfAbsent(flightKey, job) != null) { job.cancel(); return }
        // Also runs if the job is cancelled before it starts (scope cancelled), so the entry never leaks.
        job.invokeOnCompletion { inFlight.remove(flightKey, job) }
        job.start()
    }

    private fun keyOf(ref: ChapterRef) = ref.chapterId?.toString() ?: "single"

    private fun flightKey(workId: Long, key: String) = "$workId/$key"

    companion object {
        private const val MAX_MEMOIZED = 20
        const val CHAPTER_LIST_TTL_MS = 10 * 60 * 1000L
    }
}
