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

class WorkRepository(
    private val fetcher: Fetcher,
    private val cache: ChapterCache,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val chapterLists = object : LinkedHashMap<Long, List<ChapterRef>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<ChapterRef>>?) = size > MAX_MEMOIZED
    }
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    suspend fun chapters(workId: Long): Loaded<List<ChapterRef>> {
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

    suspend fun chapter(workId: Long, ref: ChapterRef): Loaded<ChapterContent> {
        val key = keyOf(ref)
        val cached = withContext(ioDispatcher) { cache.get(workId, key) }
        val html = cached ?: when (val r = fetcher.get(Ao3Urls.chapter(workId, ref.chapterId))) {
            is FetchResult.Ok -> {
                withContext(ioDispatcher) { try { cache.put(workId, key, r.html) } catch (_: IOException) {} }
                r.html
            }
            else -> return Loaded.Error(r)
        }
        return Loaded.Ok(withContext(parseDispatcher) { ChapterParser.parse(html, workId) })
    }

    fun prefetch(workId: Long, ref: ChapterRef) {
        val key = keyOf(ref)
        val flightKey = "$workId/$key"
        if (!inFlight.add(flightKey)) return
        try {
            scope.launch {
                try {
                    if (withContext(ioDispatcher) { cache.get(workId, key) } != null) return@launch
                    val r = fetcher.get(Ao3Urls.chapter(workId, ref.chapterId))
                    if (r is FetchResult.Ok) withContext(ioDispatcher) { cache.put(workId, key, r.html) }
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
