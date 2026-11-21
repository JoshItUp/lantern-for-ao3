package app.ao3reader

import app.ao3reader.ao3.net.FetchResult
import app.ao3reader.ao3.net.Fetcher
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DumpingFetcherTest {
    private class Recorder : PageDump {
        val dumped = mutableListOf<String>()
        override fun dump(url: HttpUrl, html: String) { dumped += url.toString() }
    }

    private class Stub : Fetcher {
        val gets = mutableListOf<List<Any?>>()
        val posts = mutableListOf<List<Any?>>()
        /** The final URL each result reports; defaults to the requested one. */
        var finalUrl: HttpUrl? = null
        override suspend fun get(url: HttpUrl, accept: String?, interactive: Boolean, anonymous: Boolean, forceNetwork: Boolean): FetchResult {
            gets += listOf(url, accept, interactive, anonymous)
            return FetchResult.Ok("<html/>", finalUrl ?: url)
        }
        override suspend fun post(url: HttpUrl, fields: List<Pair<String, String>>, accept: String?, interactive: Boolean): FetchResult {
            posts += listOf(url, fields, accept, interactive)
            return FetchResult.Ok("<html>posted</html>", finalUrl ?: url)
        }
    }

    private val base = "https://archiveofourown.org"

    @Test fun `passes every argument through`() = runTest {
        val stub = Stub()
        val f = DumpingFetcher(stub, Recorder())
        val u = "$base/works/1".toHttpUrl()
        f.get(u, accept = "application/json", interactive = false, anonymous = true)
        f.post(u, listOf("a" to "b"), accept = "text/html", interactive = false)
        assertEquals(listOf<Any?>(u, "application/json", false, true), stub.gets.single())
        assertEquals(listOf<Any?>(u, listOf("a" to "b"), "text/html", false), stub.posts.single())
    }

    @Test fun `dump skips POST and login URLs`() = runTest {
        val stub = Stub()
        val rec = Recorder()
        val f = DumpingFetcher(stub, rec)
        assertTrue(f.post("$base/works/1/kudos".toHttpUrl(), listOf("a" to "b")) is FetchResult.Ok)
        f.get("$base/users/login".toHttpUrl())
        f.get("$base/users/login?return_to=%2Fworks%2F1".toHttpUrl())
        // A GET that ends on the login page after a redirect is not written either.
        stub.finalUrl = "$base/users/login".toHttpUrl()
        f.get("$base/users/me/readings".toHttpUrl())
        stub.finalUrl = null
        assertEquals(emptyList<String>(), rec.dumped)
        f.get("$base/works/2".toHttpUrl())
        assertEquals(listOf("$base/works/2"), rec.dumped)
    }
}
