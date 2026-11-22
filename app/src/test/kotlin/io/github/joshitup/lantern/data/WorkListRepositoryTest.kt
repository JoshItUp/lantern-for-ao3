package io.github.joshitup.lantern.data

import io.github.joshitup.lantern.ao3.net.FetchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkListRepositoryTest {
    private val blurbs = """<h2 class="heading">Saga</h2><ul class="series work index group">
        <li class="work blurb group" id="work_5"><h4 class="heading"><a href="/works/5">T</a></h4></li></ul>
        <ol class="pagination"><li class="next"><a href="/series/9?page=2">Next</a></li></ol>"""

    private class Calls {
        val authed = mutableListOf<String>()
        val plain = mutableListOf<String>()
    }

    private fun repo(acting: Boolean, calls: Calls, page: String = blurbs) = WorkListRepository(
        acting = { acting },
        authedGet = { u: HttpUrl -> calls.authed += u.toString(); FetchResult.Ok(page, u) },
        plainGet = { u: HttpUrl -> calls.plain += u.toString(); FetchResult.Ok(page, u) },
        parseDispatcher = Dispatchers.Unconfined,
    )

    @Test fun `signed in lists load with the session`() = runTest {
        val c = Calls()
        val r = repo(true, c).load("https://archiveofourown.org/series/9".toHttpUrl(), 2) as Loaded.Ok
        assertEquals(listOf("https://archiveofourown.org/series/9?page=2"), c.authed)
        assertTrue(c.plain.isEmpty())
        assertEquals(5L, r.value.page.works.single().workId)
        assertTrue(r.value.page.hasNext)
        assertEquals(2, r.value.page.page)
    }

    @Test fun `without an account lists load signed out`() = runTest {
        val c = Calls()
        repo(false, c).load("https://archiveofourown.org/users/bob".toHttpUrl(), 1)
        assertEquals(listOf("https://archiveofourown.org/users/bob/works"), c.plain)
        assertTrue(c.authed.isEmpty())
    }

    @Test fun `non-AO3 urls are never requested`() = runTest {
        val c = Calls()
        val r = repo(false, c).load("https://evil.example/series/9".toHttpUrl(), 1)
        assertTrue(r is Loaded.Error)
        assertTrue(c.plain.isEmpty() && c.authed.isEmpty())
    }

    @Test fun `signed-in pages feed the subscribe state, signed-out pages don't`() = runTest {
        val fed = mutableListOf<Triple<String, String, String>>()
        val sink = { list: HttpUrl, html: String, pageUrl: HttpUrl -> fed += Triple(list.toString(), html, pageUrl.toString()) }
        val c = Calls()
        val list = "https://archiveofourown.org/series/9".toHttpUrl()
        WorkListRepository({ true }, { u -> FetchResult.Ok(blurbs, u) }, { u -> FetchResult.Ok(blurbs, u) }, Dispatchers.Unconfined, sink)
            .load(list, 2)
        assertEquals(listOf(Triple("https://archiveofourown.org/series/9", blurbs, "https://archiveofourown.org/series/9?page=2")), fed)
        WorkListRepository({ false }, { u -> FetchResult.Ok(blurbs, u) }, { u -> c.plain += u.toString(); FetchResult.Ok(blurbs, u) }, Dispatchers.Unconfined, sink)
            .load(list, 1)
        assertEquals(1, fed.size)
    }

    @Test fun `errors pass through`() = runTest {
        val r = WorkListRepository({ false }, { FetchResult.Blocked }, { FetchResult.Blocked }, Dispatchers.Unconfined)
            .load("https://archiveofourown.org/series/9".toHttpUrl(), 1)
        assertEquals(Loaded.Error(FetchResult.Blocked), r)
    }
}
