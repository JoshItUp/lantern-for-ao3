package io.github.joshitup.lantern.data

import io.github.joshitup.lantern.ao3.model.SearchQuery
import io.github.joshitup.lantern.ao3.net.FetchResult
import io.github.joshitup.lantern.ao3.parse.Ao3Urls
import io.github.joshitup.lantern.ao3.search.toUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRepositoryTest {
    @Test fun `search parses page`() = runTest {
        val f = FakeFetcher()
        val q = SearchQuery(anyField = "x")
        f.ok(q.toUrl(1), """<ol><li class="blurb" id="work_5"><h4 class="heading"><a href="/works/5">T</a></h4></li></ol>""")
        val r = SearchRepository(f, Dispatchers.Unconfined).search(q, 1) as Loaded.Ok
        assertEquals(5L, r.value.works.single().workId)
    }

    @Test fun `search error passes through`() = runTest {
        val r = SearchRepository(FakeFetcher(), Dispatchers.Unconfined).search(SearchQuery(), 1)
        assertEquals(Loaded.Error(FetchResult.NotFound), r)
    }

    @Test fun `autocomplete short term makes no request`() = runTest {
        val f = FakeFetcher()
        val repo = SearchRepository(f, Dispatchers.Unconfined)
        assertTrue(repo.autocomplete("fandom", " a ").isEmpty())
        assertTrue(f.calls.isEmpty())
    }

    @Test fun `autocomplete parses names and non-ok is empty`() = runTest {
        val f = FakeFetcher()
        f.ok(Ao3Urls.autocomplete("fandom", "ab"), """[{"id":"Abc","name":"Abc"}]""")
        val repo = SearchRepository(f, Dispatchers.Unconfined)
        assertEquals(listOf("Abc"), repo.autocomplete("fandom", " ab "))
        assertTrue(repo.autocomplete("fandom", "zz").isEmpty())
    }

    @Test fun `autocomplete requests with the json accept header`() = runTest {
        val f = FakeFetcher()
        f.ok(Ao3Urls.autocomplete("tag", "ab"), "[]")
        SearchRepository(f, Dispatchers.Unconfined).autocomplete("tag", "ab")
        assertEquals(listOf(SearchRepository.AUTOCOMPLETE_ACCEPT), f.accepts)
        assertEquals("application/json, text/javascript, */*; q=0.01", SearchRepository.AUTOCOMPLETE_ACCEPT)
    }

    @Test fun `search requests without an accept override`() = runTest {
        val f = FakeFetcher()
        SearchRepository(f, Dispatchers.Unconfined).search(SearchQuery(), 1)
        assertEquals(listOf<String?>(null), f.accepts)
    }

    private fun json(vararg names: String) = names.joinToString(",", "[", "]") { """{"id":"$it","name":"$it"}""" }

    @Test fun `autocomplete cache hit makes no request`() = runTest {
        val f = FakeFetcher()
        f.ok(Ao3Urls.autocomplete("fandom", "ab"), json("Abc"))
        val repo = SearchRepository(f, Dispatchers.Unconfined)
        assertEquals(listOf("Abc"), repo.autocomplete("fandom", "ab"))
        assertEquals(listOf("Abc"), repo.autocomplete("fandom", " AB "))
        assertEquals(1, f.calls.size)
        assertEquals(LocalSuggestions(listOf("Abc"), final = true), repo.cachedAutocomplete("fandom", "Ab"))
    }

    @Test fun `autocomplete cache is per kind and failures are not cached`() = runTest {
        val f = FakeFetcher()
        f.ok(Ao3Urls.autocomplete("fandom", "ab"), json("Abc"))
        val repo = SearchRepository(f, Dispatchers.Unconfined)
        repo.autocomplete("fandom", "ab")
        assertNull(repo.cachedAutocomplete("character", "ab"))
        repo.autocomplete("fandom", "zz")
        repo.autocomplete("fandom", "zz")
        assertEquals(3, f.calls.size)
    }

    @Test fun `complete cached prefix narrows locally and is final`() = runTest {
        val f = FakeFetcher()
        f.ok(Ao3Urls.autocomplete("fandom", "ha"), json("Harbor Lights - Book Series", "Hanover (TV)", "Haiku Club!!"))
        val repo = SearchRepository(f, Dispatchers.Unconfined)
        repo.autocomplete("fandom", "ha")
        assertEquals(
            LocalSuggestions(listOf("Harbor Lights - Book Series"), final = true),
            repo.cachedAutocomplete("fandom", "Harbor l"),
        )
        assertEquals(1, f.calls.size)
    }

    @Test fun `full cached prefix narrows locally but is not final`() = runTest {
        val f = FakeFetcher()
        val ten = (1..10).map { "Harbor $it" }
        f.ok(Ao3Urls.autocomplete("fandom", "ha"), json(*ten.toTypedArray()))
        val repo = SearchRepository(f, Dispatchers.Unconfined)
        repo.autocomplete("fandom", "ha")
        assertEquals(LocalSuggestions(listOf("Harbor 1", "Harbor 10"), final = false), repo.cachedAutocomplete("fandom", "harbor 1"))
    }

    @Test fun `longest cached prefix wins`() = runTest {
        val f = FakeFetcher()
        f.ok(Ao3Urls.autocomplete("fandom", "ha"), json(*(1..10).map { "Ha $it" }.toTypedArray()))
        f.ok(Ao3Urls.autocomplete("fandom", "harb"), json("Harbor Lights"))
        val repo = SearchRepository(f, Dispatchers.Unconfined)
        repo.autocomplete("fandom", "ha")
        repo.autocomplete("fandom", "harb")
        assertEquals(LocalSuggestions(listOf("Harbor Lights"), final = true), repo.cachedAutocomplete("fandom", "harbor"))
    }

    @Test fun `autocomplete cache keeps about 100 recent entries`() = runTest {
        val f = FakeFetcher()
        val repo = SearchRepository(f, Dispatchers.Unconfined)
        for (i in 0..100) {
            f.ok(Ao3Urls.autocomplete("tag", "t$i"), json("T$i"))
            repo.autocomplete("tag", "t$i")
        }
        assertNull(repo.cachedAutocomplete("tag", "t0"))
        assertEquals(listOf("T100"), repo.cachedAutocomplete("tag", "t100")?.names)
    }

    @Test fun `ok response with an html body is empty but not cached`() = runTest {
        val f = FakeFetcher()
        f.ok(Ao3Urls.autocomplete("fandom", "ha"), "<html><title>Just a moment...</title></html>")
        val repo = SearchRepository(f, Dispatchers.Unconfined)
        assertTrue(repo.autocomplete("fandom", "ha").isEmpty())
        assertNull(repo.cachedAutocomplete("fandom", "ha"))
        assertNull(repo.cachedAutocomplete("fandom", "harbor"))   // no poisoned prefix
        repo.autocomplete("fandom", "ha")
        assertEquals(2, f.calls.size)
    }

    @Test fun `real empty array is cached`() = runTest {
        val f = FakeFetcher()
        f.ok(Ao3Urls.autocomplete("fandom", "qx"), "[]")
        val repo = SearchRepository(f, Dispatchers.Unconfined)
        assertTrue(repo.autocomplete("fandom", "qx").isEmpty())
        assertTrue(repo.autocomplete("fandom", "qx").isEmpty())
        assertEquals(1, f.calls.size)
        assertEquals(LocalSuggestions(emptyList(), final = true), repo.cachedAutocomplete("fandom", "qxz"))
    }

    @Test fun `non-ok results are never cached`() = runTest {
        val f = FakeFetcher()
        val url = Ao3Urls.autocomplete("fandom", "ab")
        f.responses[url.toString()] = FetchResult.Blocked
        val repo = SearchRepository(f, Dispatchers.Unconfined)
        repo.autocomplete("fandom", "ab")
        f.responses[url.toString()] = FetchResult.RateLimited(60)
        repo.autocomplete("fandom", "ab")
        f.responses[url.toString()] = FetchResult.Failed(null)
        repo.autocomplete("fandom", "ab")
        assertNull(repo.cachedAutocomplete("fandom", "ab"))
        assertEquals(3, f.calls.size)
    }
}
