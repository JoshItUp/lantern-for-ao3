package app.ao3reader.account

import app.ao3reader.ao3.net.FetchResult
import app.ao3reader.data.FakeFetcher
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservingFetcherTest {
    private val u = "https://archiveofourown.org/works/1".toHttpUrl()

    @Test fun `observes only signed-in-capable Ok html GETs`() = runTest {
        val fake = FakeFetcher()
        fake.ok(u, "<html>page</html>")
        fake.postResponse = { _, _ -> FetchResult.Ok("<html>posted</html>", u) }
        val seen = mutableListOf<String>()
        val f = ObservingFetcher(fake) { seen += it }

        assertTrue(f.get(u) is FetchResult.Ok)
        f.get(u, anonymous = true)
        f.get(u, accept = "application/json")
        f.get("https://archiveofourown.org/works/2".toHttpUrl()) // NotFound
        f.post(u, listOf("a" to "b"), interactive = false)

        assertEquals(listOf("<html>page</html>"), seen)
        // Every argument still reaches the delegate.
        assertEquals(listOf(false, true, false, false), fake.anonymouses)
        assertEquals(listOf(null, null, "application/json", null), fake.accepts)
        assertEquals(1, fake.posts.size)
        assertEquals(listOf(false), fake.postInteractives)
    }

    @Test fun `observer failure does not fail the fetch`() = runTest {
        val fake = FakeFetcher()
        fake.ok(u, "<html/>")
        val f = ObservingFetcher(fake) { throw IllegalStateException("boom") }
        assertTrue(f.get(u) is FetchResult.Ok)
    }
}
