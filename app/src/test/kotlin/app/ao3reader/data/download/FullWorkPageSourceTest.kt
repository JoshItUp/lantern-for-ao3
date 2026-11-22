package app.ao3reader.data.download

import app.ao3reader.ao3.model.ChapterContent
import app.ao3reader.ao3.net.FetchResult
import app.ao3reader.ao3.parse.Ao3Urls
import app.ao3reader.data.AuthedFetch
import app.ao3reader.data.FakeFetcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FullWorkPageSourceTest {
    private val id = 1000000L
    private val url = Ao3Urls.fullWork(id)
    // The :ao3 module's synthetic fixture, read in place (as the other :app tests read :ao3 fixtures).
    private val work = File("../ao3/src/test/resources/fixtures/full_work_multi.html").readText()
    private val signIn = "<html><body><div id=\"main\">Please log in</div></body></html>"

    private class FakeAuthed(val fetcher: FakeFetcher = FakeFetcher(), var acting: Boolean = true) : AuthedFetch {
        override fun isActing() = acting
        override suspend fun get(url: HttpUrl, interactive: Boolean, forceNetwork: Boolean) =
            fetcher.get(url, null, interactive, false, forceNetwork)
    }

    private class Sink : ChapterSink {
        val got = mutableListOf<Pair<Int, ChapterContent>>()
        override fun chapter(index: Int, content: ChapterContent) { got += index to content }
    }

    private fun TestScope.source(f: FakeFetcher, a: AuthedFetch?) =
        FullWorkPageSource(f, a, StandardTestDispatcher(testScheduler))

    @Test fun anonymousFirst_noSignedInFetchForPublicWork() = runTest {
        val f = FakeFetcher().apply { ok(url, work) }
        val a = FakeAuthed()
        val r = source(f, a).fetch(id, false, Sink())
        assertTrue(r is SourceResult.Ok)
        assertEquals(listOf(url.toString()), f.calls)
        assertEquals(listOf(true), f.anonymouses)
        assertEquals(listOf(false), f.interactives)
        assertTrue(a.fetcher.calls.isEmpty())
        r as SourceResult.Ok
        assertEquals(3, r.chapters.size)
        assertEquals(3, r.postedRemote)
    }

    @Test fun restricted_signedIn_retriesOnceSignedInWithForceNetwork() = runTest {
        val f = FakeFetcher().apply { ok(url, signIn) }
        val a = FakeAuthed().apply { fetcher.ok(url, work) }
        val r = source(f, a).fetch(id, true, Sink())
        assertTrue(r is SourceResult.Ok)
        assertEquals(1, f.calls.size)
        assertEquals(listOf(url.toString()), a.fetcher.calls)
        assertEquals(listOf(true), a.fetcher.forceNetworks)
    }

    @Test fun restricted_signedOut_signInRequired() = runTest {
        val f = FakeFetcher().apply { ok(url, signIn) }
        val a = FakeAuthed(acting = false)
        val r = source(f, a).fetch(id, true, Sink())
        assertEquals(DownloadFailure.SIGN_IN_REQUIRED, (r as SourceResult.Failed).reason)
        assertTrue(a.fetcher.calls.isEmpty())
        val r2 = source(f, null).fetch(id, true, Sink())
        assertEquals(DownloadFailure.SIGN_IN_REQUIRED, (r2 as SourceResult.Failed).reason)
    }

    @Test fun restricted_signedIn_stillUnreadable() = runTest {
        val f = FakeFetcher().apply { ok(url, signIn) }
        val a = FakeAuthed().apply { fetcher.ok(url, signIn) }
        val r = source(f, a).fetch(id, true, Sink())
        assertEquals(DownloadFailure.UNREADABLE, (r as SourceResult.Failed).reason)
        assertEquals(1, a.fetcher.calls.size)
    }

    @Test fun notFound() = runTest { assertReason(FetchResult.NotFound, DownloadFailure.NOT_FOUND) }
    @Test fun blocked() = runTest { assertReason(FetchResult.Blocked, DownloadFailure.BLOCKED) }
    @Test fun network() = runTest {
        assertReason(FetchResult.Failed(null), DownloadFailure.NETWORK)
        assertReason(FetchResult.RateLimited(5), DownloadFailure.NETWORK)
    }

    private suspend fun TestScope.assertReason(res: FetchResult, reason: DownloadFailure) {
        val f = FakeFetcher().apply { responses[url.toString()] = res }
        val r = source(f, null).fetch(id, true, Sink())
        assertEquals(reason, (r as SourceResult.Failed).reason)
    }

    @Test fun chaptersStreamedToSinkInOrder() = runTest {
        val f = FakeFetcher().apply { ok(url, work) }
        val sink = Sink()
        source(f, null).fetch(id, false, sink)
        assertEquals(listOf(0, 1, 2), sink.got.map { it.first })
        assertEquals(listOf(1, 2, 3), sink.got.map { it.second.ref.number })
    }

    @Test fun chapterCountMismatch_unreadable() = runTest {
        val f = FakeFetcher().apply { ok(url, work.replace("3/5", "4/5")) }
        val r = source(f, null).fetch(id, false, Sink())
        assertEquals(DownloadFailure.UNREADABLE, (r as SourceResult.Failed).reason)
    }

    @Test fun actingAccount_rateLimitedAnonymous_noSignedInRetry() = runTest { assertNoRetry(FetchResult.RateLimited(5), DownloadFailure.NETWORK) }
    @Test fun actingAccount_blockedAnonymous_noSignedInRetry() = runTest { assertNoRetry(FetchResult.Blocked, DownloadFailure.BLOCKED) }

    private suspend fun TestScope.assertNoRetry(res: FetchResult, reason: DownloadFailure) {
        val f = FakeFetcher().apply { responses[url.toString()] = res }
        val a = FakeAuthed().apply { fetcher.ok(url, work) }
        val r = source(f, a).fetch(id, false, Sink())
        assertEquals(reason, (r as SourceResult.Failed).reason)
        assertTrue(a.fetcher.calls.isEmpty())
    }
}
