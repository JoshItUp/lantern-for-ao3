package app.ao3reader.data

import app.ao3reader.ao3.model.ChapterRef
import app.ao3reader.ao3.net.FetchResult
import app.ao3reader.ao3.parse.Ao3Urls
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class WorkRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val ref = ChapterRef(10L, 1, "One")

    private fun TestScopeHelper.repo() = WorkRepository(fetcher, cache, scope, dispatcher, dispatcher, clock = { now })

    private class TestScopeHelper(val fetcher: FakeFetcher, val cache: ChapterCache, val dispatcher: kotlinx.coroutines.CoroutineDispatcher) {
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var now = 1_000_000L
    }

    private fun navigateHtml(vararg ids: Long) =
        """<ol class="chapter index">""" + ids.mapIndexed { i, id -> """<li><a href="/works/1/chapters/$id">${i + 1}. C$id</a></li>""" }.joinToString("") + "</ol>"

    private fun kotlinx.coroutines.test.TestScope.h(): TestScopeHelper =
        TestScopeHelper(FakeFetcher(), ChapterCache(tmp.newFolder()), StandardTestDispatcher(testScheduler))

    @Test fun `chapter served from cache without fetch`() = runTest {
        val h = h(); h.cache.put(1, "10", CHAPTER_HTML)
        val r = h.repo().chapter(1, ref)
        assertEquals("My Work", (r as Loaded.Ok).value.workTitle)
        assertTrue(h.fetcher.calls.isEmpty())
    }

    @Test fun `chapter fetched then cached`() = runTest {
        val h = h(); h.fetcher.ok(Ao3Urls.chapter(1, 10), CHAPTER_HTML)
        val repo = h.repo()
        assertTrue(repo.chapter(1, ref) is Loaded.Ok)
        assertTrue(repo.chapter(1, ref) is Loaded.Ok)
        assertEquals(1, h.fetcher.calls.size)
        assertTrue(h.cache.get(1, "10") != null)
    }

    @Test fun `cached chapter served when network fails`() = runTest {
        val h = h(); h.cache.put(1, "10", CHAPTER_HTML)
        h.fetcher.responses[Ao3Urls.chapter(1, 10).toString()] = FetchResult.Failed(null)
        assertTrue(h.repo().chapter(1, ref) is Loaded.Ok)
    }

    @Test fun `uncached chapter with network failure returns Error`() = runTest {
        val h = h()
        val failed = FetchResult.Failed(null)
        h.fetcher.responses[Ao3Urls.chapter(1, 10).toString()] = failed
        assertEquals(Loaded.Error(failed), h.repo().chapter(1, ref))
    }

    @Test fun `single chapter work uses single key`() = runTest {
        val h = h(); h.fetcher.ok(Ao3Urls.chapter(2, null), CHAPTER_HTML)
        assertTrue(h.repo().chapter(2, ChapterRef(null, 1, "x")) is Loaded.Ok)
        assertTrue(h.cache.get(2, "single") != null)
    }

    @Test fun `prefetch writes to cache and does not fetch when already cached`() = runTest {
        val h = h(); h.fetcher.ok(Ao3Urls.chapter(1, 10), CHAPTER_HTML)
        val repo = h.repo()
        repo.prefetch(1, ref); repo.prefetch(1, ref)
        h.advanceUntilIdle(this)
        assertEquals(1, h.fetcher.calls.size)
        assertTrue(h.cache.get(1, "10") != null)
        repo.prefetch(1, ref)
        h.advanceUntilIdle(this)
        assertEquals(1, h.fetcher.calls.size)
    }

    @Test fun `prefetch fetches non-interactively, a reader load interactively`() = runTest {
        val h = h(); h.fetcher.ok(Ao3Urls.chapter(1, 10), CHAPTER_HTML); h.fetcher.ok(Ao3Urls.chapter(1, 11), CHAPTER_HTML)
        val repo = h.repo()
        repo.prefetch(1, ref)
        h.advanceUntilIdle(this)
        repo.chapter(1, ChapterRef(11L, 2, "Two"))
        assertEquals(listOf(false, true), h.fetcher.interactives)
    }

    @Test fun `prefetch of failing fetch does not throw`() = runTest {
        val h = h()
        h.fetcher.responses[Ao3Urls.chapter(1, 10).toString()] = FetchResult.Failed(null)
        h.repo().prefetch(1, ref)
        h.advanceUntilIdle(this)
        assertEquals(null, h.cache.get(1, "10"))
    }

    @Test fun `chapters memoized`() = runTest {
        val h = h()
        h.fetcher.ok(Ao3Urls.navigate(1), """<ol class="chapter index"><li><a href="/works/1/chapters/10">1. One</a></li></ol>""")
        val repo = h.repo()
        val a = repo.chapters(1) as Loaded.Ok
        repo.chapters(1)
        assertEquals(1, h.fetcher.calls.size)
        assertEquals(listOf(ChapterRef(10L, 1, "One")), a.value)
    }

    @Test fun `fresh chapter list is served from the memo without a fetch`() = runTest {
        val h = h(); h.fetcher.ok(Ao3Urls.navigate(1), navigateHtml(10))
        val repo = h.repo()
        repo.chapters(1)
        h.now += WorkRepository.CHAPTER_LIST_TTL_MS - 1
        assertEquals(1, (repo.chapters(1) as Loaded.Ok).value.size)
        assertEquals(1, h.fetcher.calls.size)
    }

    @Test fun `expired chapter list is refetched, picking up new chapters`() = runTest {
        val h = h(); h.fetcher.ok(Ao3Urls.navigate(1), navigateHtml(10))
        val repo = h.repo()
        repo.chapters(1)
        h.fetcher.ok(Ao3Urls.navigate(1), navigateHtml(10, 11))
        h.now += WorkRepository.CHAPTER_LIST_TTL_MS
        assertEquals(listOf(10L, 11L), (repo.chapters(1) as Loaded.Ok).value.map { it.chapterId })
        assertEquals(2, h.fetcher.calls.size)
        // ...and the refetched list is fresh again.
        repo.chapters(1)
        assertEquals(2, h.fetcher.calls.size)
    }

    @Test fun `expired chapter list whose refetch fails falls back to the stale list`() = runTest {
        val h = h(); h.fetcher.ok(Ao3Urls.navigate(1), navigateHtml(10))
        val repo = h.repo()
        repo.chapters(1)
        h.fetcher.responses[Ao3Urls.navigate(1).toString()] = FetchResult.Failed(null)
        h.now += WorkRepository.CHAPTER_LIST_TTL_MS + 1
        assertEquals(listOf(10L), (repo.chapters(1) as Loaded.Ok).value.map { it.chapterId })
        assertEquals(2, h.fetcher.calls.size)
    }

    @Test fun `chapter waits for an in-flight prefetch of the same chapter instead of fetching it again`() = runTest {
        val h = h(); h.fetcher.ok(Ao3Urls.chapter(1, 10), CHAPTER_HTML)
        val gate = CompletableDeferred<Unit>().also { h.fetcher.gate = it }
        val repo = h.repo()
        repo.prefetch(1, ref)
        runCurrent()
        assertEquals(1, h.fetcher.calls.size)   // the prefetch is waiting on the network
        val load = async { repo.chapter(1, ref) }
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("My Work", (load.await() as Loaded.Ok).value.workTitle)
        assertEquals(1, h.fetcher.calls.size)
    }

    @Test fun `chapter fetches itself when the in-flight prefetch was blocked`() = runTest {
        val h = h(); h.fetcher.ok(Ao3Urls.chapter(1, 10), CHAPTER_HTML)
        // A challenge: the non-interactive prefetch is Blocked, the interactive load solves it and gets through.
        h.fetcher.respond = { _, interactive -> if (interactive) null else FetchResult.Blocked }
        val gate = CompletableDeferred<Unit>().also { h.fetcher.gate = it }
        val repo = h.repo()
        repo.prefetch(1, ref)
        runCurrent()
        val load = async { repo.chapter(1, ref) }
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(load.await() is Loaded.Ok)
        assertEquals(listOf(false, true), h.fetcher.interactives)
    }

    @Test fun `chapters errors are not memoized`() = runTest {
        val h = h()
        val repo = h.repo()
        assertTrue(repo.chapters(1) is Loaded.Error)
        h.fetcher.ok(Ao3Urls.navigate(1), """<ol class="chapter index"><li><a href="/works/1/chapters/10">1. One</a></li></ol>""")
        assertTrue(repo.chapters(1) is Loaded.Ok)
    }

    @Test fun `ok but non-chapter page is not cached and returns Error`() = runTest {
        val h = h(); h.fetcher.ok(Ao3Urls.chapter(1, 10), "<html><body>Just a moment</body></html>")
        val r = h.repo().chapter(1, ref)
        assertTrue(r is Loaded.Error)
        assertEquals(null, h.cache.get(1, "10"))
    }

    @Test fun `corrupt cached entry is evicted and refetched`() = runTest {
        val h = h(); h.cache.put(1, "10", "<html>garbage</html>")
        h.fetcher.ok(Ao3Urls.chapter(1, 10), CHAPTER_HTML)
        assertTrue(h.repo().chapter(1, ref) is Loaded.Ok)
        assertEquals(1, h.fetcher.calls.size)
        assertEquals(CHAPTER_HTML, h.cache.get(1, "10"))
    }

    @Test fun `prefetch of non-chapter ok page writes nothing`() = runTest {
        val h = h(); h.fetcher.ok(Ao3Urls.chapter(1, 10), "<html>Just a moment</html>")
        h.repo().prefetch(1, ref)
        h.advanceUntilIdle(this)
        assertEquals(1, h.fetcher.calls.size)
        assertEquals(null, h.cache.get(1, "10"))
    }

    // --- Signed-in History visits and signed-out prefetch (spec 5.4) ---

    /** Session stand-in: signed-in GETs answered by [fetcher] (a separate fake so calls are told apart). */
    private class FakeAuthed(val fetcher: FakeFetcher = FakeFetcher(), var acting: Boolean = true) : AuthedFetch {
        override fun isActing() = acting
        override suspend fun get(url: okhttp3.HttpUrl, interactive: Boolean, forceNetwork: Boolean) =
            fetcher.get(url, null, interactive, false, forceNetwork)
    }

    private class FakeSink : ActionSink {
        val updates = mutableListOf<Pair<Long, String>>()
        override fun update(workId: Long, html: String, pageUrl: okhttp3.HttpUrl) { updates += workId to pageUrl.toString() }
    }

    private fun TestScopeHelper.signedInRepo(authed: FakeAuthed, sink: FakeSink) =
        WorkRepository(fetcher, cache, scope, dispatcher, dispatcher, clock = { now }, authed = authed, actions = sink)

    @Test fun `prefetch requests are anonymous and non-interactive`() = runTest {
        val h = h(); h.fetcher.ok(Ao3Urls.chapter(1, 10), CHAPTER_HTML)
        val authed = FakeAuthed()
        h.signedInRepo(authed, FakeSink()).prefetch(1, ref)
        advanceUntilIdle()
        assertEquals(listOf(true), h.fetcher.anonymouses)
        assertEquals(listOf(false), h.fetcher.interactives)
        assertTrue(authed.fetcher.calls.isEmpty())
        assertTrue(h.cache.get(1, "10") != null)
    }

    @Test fun `cache miss while signed in fetches signed in, which is the visit`() = runTest {
        val h = h(); val authed = FakeAuthed(); val sink = FakeSink()
        authed.fetcher.ok(Ao3Urls.chapter(1, 10), CHAPTER_HTML)
        val repo = h.signedInRepo(authed, sink)
        assertTrue(repo.chapter(1, ref) is Loaded.Ok)
        advanceUntilIdle()
        repo.chapter(1, ref)            // now a cache hit, soon after the visit: no second visit
        advanceUntilIdle()
        assertEquals(listOf(true), authed.fetcher.interactives)
        assertTrue(h.fetcher.calls.isEmpty())
        assertEquals(listOf(1L to Ao3Urls.chapter(1, 10).toString()), sink.updates)
        assertEquals(CHAPTER_HTML, h.cache.get(1, "10"))
    }

    @Test fun `a cache hit while signed in triggers exactly one background visit and updates the actions state`() = runTest {
        val h = h(); h.cache.put(1, "10", CHAPTER_HTML)
        val authed = FakeAuthed(); val sink = FakeSink()
        val signedInHtml = CHAPTER_HTML.replace("Hello", "Hello signed in")
        authed.fetcher.ok(Ao3Urls.chapter(1, 10), signedInHtml)
        val repo = h.signedInRepo(authed, sink)
        val r = repo.chapter(1, ref)
        // Shown from the cache before any network.
        assertTrue(r is Loaded.Ok)
        assertTrue(authed.fetcher.calls.isEmpty())
        advanceUntilIdle()
        repo.chapter(1, ref)            // opened again soon after: no second visit
        advanceUntilIdle()
        assertEquals(1, authed.fetcher.calls.size)
        assertEquals(listOf(false), authed.fetcher.interactives)
        assertEquals(listOf(1L to Ao3Urls.chapter(1, 10).toString()), sink.updates)
        assertTrue(h.fetcher.calls.isEmpty())
        // The cache entry is refreshed with the signed-in page.
        assertEquals(signedInHtml, h.cache.get(1, "10"))
    }

    @Test fun `a cache hit visits again once the debounce window has passed`() = runTest {
        val h = h(); h.cache.put(1, "10", CHAPTER_HTML)
        val authed = FakeAuthed(); authed.fetcher.ok(Ao3Urls.chapter(1, 10), CHAPTER_HTML)
        val repo = h.signedInRepo(authed, FakeSink())
        repo.chapter(1, ref); advanceUntilIdle()
        h.now += WorkRepository.VISIT_DEBOUNCE_MS
        repo.chapter(1, ref); advanceUntilIdle()
        assertEquals(2, authed.fetcher.calls.size)
    }

    @Test fun `concurrent cache hits launch one visit`() = runTest {
        val h = h(); h.cache.put(1, "10", CHAPTER_HTML)
        val authed = FakeAuthed(); authed.fetcher.ok(Ao3Urls.chapter(1, 10), CHAPTER_HTML)
        val gate = CompletableDeferred<Unit>().also { authed.fetcher.gate = it }
        val repo = h.signedInRepo(authed, FakeSink())
        repo.chapter(1, ref); runCurrent()
        repo.chapter(1, ref); runCurrent()
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(1, authed.fetcher.calls.size)
    }

    @Test fun `a visit that is not a chapter page leaves the cache and the actions state alone`() = runTest {
        val h = h(); h.cache.put(1, "10", CHAPTER_HTML)
        val authed = FakeAuthed(); val sink = FakeSink()
        authed.fetcher.ok(Ao3Urls.chapter(1, 10), "<html>Just a moment</html>")
        h.signedInRepo(authed, sink).chapter(1, ref)
        advanceUntilIdle()
        assertEquals(1, authed.fetcher.calls.size)
        assertTrue(sink.updates.isEmpty())
        assertEquals(CHAPTER_HTML, h.cache.get(1, "10"))
    }

    @Test fun `swiping to a prefetched chapter while signed in shows it and visits once`() = runTest {
        val h = h(); h.fetcher.ok(Ao3Urls.chapter(1, 10), CHAPTER_HTML)
        val authed = FakeAuthed(); authed.fetcher.ok(Ao3Urls.chapter(1, 10), CHAPTER_HTML)
        val repo = h.signedInRepo(authed, FakeSink())
        repo.prefetch(1, ref); advanceUntilIdle()
        assertTrue(repo.chapter(1, ref) is Loaded.Ok)
        advanceUntilIdle()
        assertEquals(1, h.fetcher.calls.size)       // the anonymous prefetch
        assertEquals(1, authed.fetcher.calls.size)  // the visit
    }

    @Test fun `the signed-in miss fetch and the background visit bypass the HTTP cache`() = runTest {
        val h = h(); h.cache.put(1, "10", CHAPTER_HTML)
        val authed = FakeAuthed()
        authed.fetcher.ok(Ao3Urls.chapter(1, 10), CHAPTER_HTML)
        authed.fetcher.ok(Ao3Urls.chapter(1, 11), CHAPTER_HTML)
        val repo = h.signedInRepo(authed, FakeSink())
        repo.chapter(1, ref); advanceUntilIdle()                          // cache hit: background visit
        repo.chapter(1, ChapterRef(11L, 2, "Two")); advanceUntilIdle()    // cache miss: the fetch is the visit
        assertEquals(listOf(Ao3Urls.chapter(1, 10).toString(), Ao3Urls.chapter(1, 11).toString()), authed.fetcher.calls)
        assertEquals(listOf(true, true), authed.fetcher.forceNetworks)
    }

    @Test fun `an in-flight prefetch joined by a signed-in chapter load still gives exactly one visit`() = runTest {
        val h = h(); h.fetcher.ok(Ao3Urls.chapter(1, 10), CHAPTER_HTML)
        val gate = CompletableDeferred<Unit>().also { h.fetcher.gate = it }
        val authed = FakeAuthed(); val sink = FakeSink()
        authed.fetcher.ok(Ao3Urls.chapter(1, 10), CHAPTER_HTML)
        val repo = h.signedInRepo(authed, sink)
        repo.prefetch(1, ref)
        runCurrent()
        val load = async { repo.chapter(1, ref) }     // joins the gated anonymous prefetch
        runCurrent()
        assertTrue(authed.fetcher.calls.isEmpty())
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(load.await() is Loaded.Ok)
        repo.chapter(1, ref); advanceUntilIdle()      // opened again soon after: still one
        assertEquals(listOf(true), h.fetcher.anonymouses)
        assertEquals(1, authed.fetcher.calls.size)
        assertEquals(1, sink.updates.size)
    }

    @Test fun `signed out triggers no visit`() = runTest {
        val h = h(); h.cache.put(1, "10", CHAPTER_HTML)
        h.fetcher.ok(Ao3Urls.chapter(1, 11), CHAPTER_HTML)
        val authed = FakeAuthed(acting = false); val sink = FakeSink()
        val repo = h.signedInRepo(authed, sink)
        assertTrue(repo.chapter(1, ref) is Loaded.Ok)                          // cache hit
        assertTrue(repo.chapter(1, ChapterRef(11L, 2, "Two")) is Loaded.Ok)    // cache miss: plain fetch
        advanceUntilIdle()
        assertTrue(authed.fetcher.calls.isEmpty())
        assertTrue(sink.updates.isEmpty())
        assertEquals(listOf(false), h.fetcher.anonymouses)
    }

    @Test fun `restricted work loads after sign-in`() = runTest {
        val h = h(); val authed = FakeAuthed(acting = false)
        val loginPage = "<html><body>Sorry, you need to log in to see the chapters of this work.</body></html>"
        h.fetcher.ok(Ao3Urls.chapter(1, 10), loginPage)
        authed.fetcher.ok(Ao3Urls.chapter(1, 10), CHAPTER_HTML)
        val repo = h.signedInRepo(authed, FakeSink())
        repo.prefetch(1, ref); advanceUntilIdle()   // the anonymous prefetch got the sign-in page
        val first = repo.chapter(1, ref) as Loaded.Error
        assertTrue((first.result as FetchResult.Failed).cause is NotAChapterPageException)
        assertEquals(null, h.cache.get(1, "10"))
        authed.acting = true                         // the owner signs in, then taps Retry
        assertTrue(repo.chapter(1, ref) is Loaded.Ok)
        assertEquals(listOf(true), authed.fetcher.interactives)
    }

    @Test fun `restricted chapter opened while signed in loads through the signed-in fetch`() = runTest {
        val h = h(); val authed = FakeAuthed()
        h.fetcher.ok(Ao3Urls.chapter(1, 10), "<html><body>Please log in.</body></html>")
        authed.fetcher.ok(Ao3Urls.chapter(1, 10), CHAPTER_HTML)
        assertTrue(h.signedInRepo(authed, FakeSink()).chapter(1, ref) is Loaded.Ok)
        assertTrue(h.fetcher.calls.isEmpty())
    }

    @Test fun `chapter list is fetched signed in while an account acts`() = runTest {
        val h = h(); val authed = FakeAuthed()
        authed.fetcher.ok(Ao3Urls.navigate(1), navigateHtml(10))
        assertEquals(1, (h.signedInRepo(authed, FakeSink()).chapters(1) as Loaded.Ok).value.size)
        assertTrue(h.fetcher.calls.isEmpty())
    }

    @Test fun `an empty chapter list is not memoized, so a sign-in can fetch the real one`() = runTest {
        val h = h(); val authed = FakeAuthed(acting = false)
        h.fetcher.ok(Ao3Urls.navigate(1), "<html><body>Please log in.</body></html>")
        authed.fetcher.ok(Ao3Urls.navigate(1), navigateHtml(10))
        val repo = h.signedInRepo(authed, FakeSink())
        assertTrue((repo.chapters(1) as Loaded.Ok).value.isEmpty())
        authed.acting = true
        assertEquals(1, (repo.chapters(1) as Loaded.Ok).value.size)
    }

    @Test fun visitInBackground_debouncedAndFeedsActions() = runTest {
        val h = h(); val authed = FakeAuthed(); val sink = FakeSink()
        val signedInHtml = CHAPTER_HTML.replace("Hello", "Hello signed in")
        authed.fetcher.ok(Ao3Urls.chapter(1, 10), signedInHtml)
        val repo = h.signedInRepo(authed, sink)
        repo.visitInBackground(1, ref)
        repo.visitInBackground(1, ref)   // within the debounce window: no second visit
        advanceUntilIdle()
        assertEquals(listOf(Ao3Urls.chapter(1, 10).toString()), authed.fetcher.calls)
        assertEquals(listOf(false), authed.fetcher.interactives)
        assertEquals(listOf(true), authed.fetcher.forceNetworks)
        assertEquals(listOf(1L to Ao3Urls.chapter(1, 10).toString()), sink.updates)
        assertEquals(signedInHtml, h.cache.get(1, "10"))
        assertTrue(h.fetcher.calls.isEmpty())
        h.now += WorkRepository.VISIT_DEBOUNCE_MS
        repo.visitInBackground(1, ref); advanceUntilIdle()
        assertEquals(2, authed.fetcher.calls.size)
        // Signed out: no visit at all.
        authed.acting = false
        h.now += WorkRepository.VISIT_DEBOUNCE_MS
        repo.visitInBackground(1, ref); advanceUntilIdle()
        assertEquals(2, authed.fetcher.calls.size)
    }

    @Test fun visitFailure_freesDebounce_nextOpenRevisits() = runTest {
        val h = h(); val authed = FakeAuthed()
        val repo = h.signedInRepo(authed, FakeSink())
        // Not Ok (no response set: NotFound): the next open retries at once.
        repo.visitInBackground(1, ref); advanceUntilIdle()
        repo.visitInBackground(1, ref); advanceUntilIdle()
        assertEquals(2, authed.fetcher.calls.size)
        // The GET throws: also freed.
        authed.fetcher.respond = { _, _ -> throw java.io.IOException("down") }
        repo.visitInBackground(1, ref); advanceUntilIdle()
        authed.fetcher.respond = null
        authed.fetcher.ok(Ao3Urls.chapter(1, 10), CHAPTER_HTML)
        repo.visitInBackground(1, ref); advanceUntilIdle()
        assertEquals(4, authed.fetcher.calls.size)
        // A successful visit still debounces.
        repo.visitInBackground(1, ref); advanceUntilIdle()
        assertEquals(4, authed.fetcher.calls.size)
    }

    @Test fun onChapterPage_calledForSignedInPages() = runTest {
        val h = h(); val authed = FakeAuthed()
        val missHtml = CHAPTER_HTML.replace("Hello", "miss")
        val visitHtml = CHAPTER_HTML.replace("Hello", "visit")
        authed.fetcher.ok(Ao3Urls.chapter(1, 10), missHtml)
        authed.fetcher.ok(Ao3Urls.chapter(1, 11), visitHtml)
        h.cache.put(1, "11", CHAPTER_HTML)
        h.fetcher.ok(Ao3Urls.chapter(1, 12), CHAPTER_HTML)
        h.fetcher.ok(Ao3Urls.chapter(1, 13), CHAPTER_HTML)
        val repo = h.signedInRepo(authed, FakeSink())
        val seen = mutableListOf<Pair<Long, String>>()
        repo.onChapterPage = { id, html -> seen += id to html }
        repo.chapter(1, ref); advanceUntilIdle()                          // signed-in foreground load
        repo.chapter(1, ChapterRef(11L, 2, "Two")); advanceUntilIdle()    // cache hit: background visit
        repo.prefetch(1, ChapterRef(12L, 3, "Three")); advanceUntilIdle() // anonymous prefetch: not reported
        authed.acting = false
        repo.chapter(1, ChapterRef(13L, 4, "Four")); advanceUntilIdle()   // signed out: not reported
        assertEquals(listOf(1L to missHtml, 1L to visitHtml), seen)
    }

    private fun TestScopeHelper.advanceUntilIdle(s: kotlinx.coroutines.test.TestScope) = s.advanceUntilIdle()
}
