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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** What the reader needs from [WorkRepository]; an interface so the ViewModel can be tested with a fake. */
interface ChapterSource {
    suspend fun chapters(workId: Long): Loaded<List<ChapterRef>>
    suspend fun chapter(workId: Long, ref: ChapterRef): Loaded<ChapterContent>
    fun prefetch(workId: Long, ref: ChapterRef)
    /** Lists found after [chapters] returned (a downloaded work: once per [chapters] call, when its online check ends); none by default. */
    fun listUpdates(workId: Long): Flow<List<ChapterRef>> = emptyFlow()
}

/** An Ok response that has no chapter text (e.g. AO3's sign-in page for a registered-users-only work). */
class NotAChapterPageException : IllegalStateException("not a chapter page")

/** Signed-in GETs (the owner's [app.ao3reader.account.Session]); an interface so tests use a fake. */
interface AuthedFetch {
    /** True while an account acts: chapter loads then go signed in and count as AO3 History visits. */
    fun isActing(): Boolean
    /** [forceNetwork]: never served from the HTTP cache (a History visit must reach AO3). */
    suspend fun get(url: HttpUrl, interactive: Boolean, forceNetwork: Boolean = false): FetchResult
}

/** Receives every signed-in chapter page, so the reader actions have fresh forms ([ActionRepository.update]). */
fun interface ActionSink {
    fun update(workId: Long, html: String, pageUrl: HttpUrl)
}

class WorkRepository(
    private val fetcher: Fetcher,
    private val cache: ChapterCache,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Null: never signed in (every load is a plain fetch). */
    private val authed: AuthedFetch? = null,
    private val actions: ActionSink? = null,
) : ChapterSource {
    private class Memo(val list: List<ChapterRef>, val fetchedAt: Long)

    private val chapterLists = object : LinkedHashMap<Long, Memo>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Memo>?) = size > MAX_MEMOIZED
    }
    /** Prefetches running now, by "workId/key"; [chapter] waits for one rather than fetching the same page twice. */
    private val inFlight = ConcurrentHashMap<String, Job>()
    /** When each chapter ("workId/key") was last visited signed in; debounces [visitInBackground]. */
    private val lastVisit = ConcurrentHashMap<String, Long>()

    /**
     * Gets every signed-in chapter page this repository receives (foreground loads and background visits;
     * never anonymous loads or prefetches). Called on a background thread; must return quickly.
     */
    @Volatile var onChapterPage: ((workId: Long, html: String) -> Unit)? = null

    /**
     * Memoized for [CHAPTER_LIST_TTL_MS], so a work in progress picks up new chapters. An expired list
     * is refetched; if that fails (or finds no chapters) the expired list is served instead of an error.
     */
    override suspend fun chapters(workId: Long): Loaded<List<ChapterRef>> {
        val memo = synchronized(chapterLists) { chapterLists[workId] }
        if (memo != null && clock() - memo.fetchedAt < CHAPTER_LIST_TTL_MS) return Loaded.Ok(memo.list)
        val url = Ao3Urls.navigate(workId)
        val signedIn = authed?.takeIf { it.isActing() }
        return when (val r = signedIn?.get(url, interactive = true) ?: fetcher.get(url)) {
            is FetchResult.Ok -> {
                val list = withContext(parseDispatcher) { NavigateParser.parse(r.html) }
                if (list.isEmpty() && memo != null) return Loaded.Ok(memo.list)
                // An empty list (e.g. the sign-in page of a restricted work) is not memoized: after
                // signing in, the next open fetches the real one.
                if (list.isNotEmpty()) synchronized(chapterLists) { chapterLists[workId] = Memo(list, clock()) }
                Loaded.Ok(list)
            }
            else -> if (memo != null) Loaded.Ok(memo.list) else Loaded.Error(r)
        }
    }

    override suspend fun chapter(workId: Long, ref: ChapterRef): Loaded<ChapterContent> {
        val key = keyOf(ref)
        // A prefetch of this chapter is under way (a swipe soon after opening): let it land in the cache.
        inFlight[flightKey(workId, key)]?.join()
        val signedIn = authed?.takeIf { it.isActing() }
        val cached = withContext(ioDispatcher) { cache.get(workId, key) }
        if (cached != null) {
            val parsed = parseValid(cached, workId)
            if (parsed != null) {
                // Shown from the cache at once; the History visit goes out in the background.
                if (signedIn != null) visitInBackground(workId, ref)
                return Loaded.Ok(parsed)
            }
            withContext(ioDispatcher) { cache.remove(workId, key) }
        }
        val url = Ao3Urls.chapter(workId, ref.chapterId)
        // Signed in, this fetch is the visit (and loads registered-users-only works).
        if (signedIn != null) lastVisit[flightKey(workId, key)] = clock()
        // forceNetwork: an HTTP-cached copy would show the chapter but record no visit.
        val r = signedIn?.get(url, interactive = true, forceNetwork = true) ?: fetcher.get(url)
        if (r !is FetchResult.Ok) return Loaded.Error(r)
        val parsed = parseValid(r.html, workId)
            ?: return Loaded.Error(FetchResult.Failed(NotAChapterPageException()))
        withContext(ioDispatcher) { try { cache.put(workId, key, r.html) } catch (_: IOException) {} }
        if (signedIn != null) {
            feedActions(workId, r)
            notePage(workId, r.html)
        }
        return Loaded.Ok(parsed)
    }

    /**
     * A background signed-in GET of a chapter shown without a fetch (from the cache or a download), so it
     * appears in the owner's AO3 History. Its page feeds [actions], goes to [onPage] and [onChapterPage], and
     * refreshes the chapter-cache entry; the reader is not re-rendered. Nothing while no account acts.
     * At most one per chapter within [VISIT_DEBOUNCE_MS].
     */
    fun visitInBackground(workId: Long, ref: ChapterRef, onPage: (html: String) -> Unit = {}) {
        val signedIn = authed?.takeIf { it.isActing() } ?: return
        val key = keyOf(ref)
        val fk = flightKey(workId, key)
        val now = clock()
        if (lastVisit.size > MAX_VISITS_TRACKED) lastVisit.entries.removeIf { now - it.value >= VISIT_DEBOUNCE_MS }
        while (true) {
            val last = lastVisit[fk]
            if (last != null && now - last < VISIT_DEBOUNCE_MS) return
            val won = if (last == null) lastVisit.putIfAbsent(fk, now) == null else lastVisit.replace(fk, last, now)
            if (won) break
        }
        scope.launch {
            try {
                val r = signedIn.get(Ao3Urls.chapter(workId, ref.chapterId), interactive = false, forceNetwork = true)
                if (r !is FetchResult.Ok) return@launch
                if (!withContext(parseDispatcher) { ChapterParser.looksLikeChapterPage(r.html) }) return@launch
                feedActions(workId, r)
                if (parseValid(r.html, workId) != null) {
                    withContext(ioDispatcher) { try { cache.put(workId, key, r.html) } catch (_: IOException) {} }
                }
                notePage(workId, r.html)
                onPage(r.html)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            }
        }
    }

    private fun notePage(workId: Long, html: String) {
        val hook = onChapterPage ?: return
        try { hook(workId, html) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
    }

    private suspend fun feedActions(workId: Long, r: FetchResult.Ok) {
        val sink = actions ?: return
        withContext(parseDispatcher) {
            try { sink.update(workId, r.html, r.finalUrl) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
        }
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
                // Background work: a challenge is left for the next reader load to solve. Signed out, so
                // a prefetch adds no AO3 History entry or hit the owner didn't intend.
                val r = fetcher.get(Ao3Urls.chapter(workId, ref.chapterId), interactive = false, anonymous = true)
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
        private const val MAX_VISITS_TRACKED = 256
        const val CHAPTER_LIST_TTL_MS = 10 * 60 * 1000L
        /** A chapter opened again within this long after a signed-in visit is not visited again. */
        const val VISIT_DEBOUNCE_MS = 60_000L
    }
}
