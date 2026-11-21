package app.ao3reader.ao3.parse

import app.ao3reader.ao3.model.AuthorLink
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Ao3UrlsWorkListTest {
    private fun list(s: String) = Ao3Urls.workList(s.toHttpUrl())?.toString()

    @Test fun `author works url uses the pseud only when it differs`() {
        assertEquals("https://archiveofourown.org/users/bob/works", Ao3Urls.authorWorks(AuthorLink("bob", "bob", "bob")).toString())
        assertEquals("https://archiveofourown.org/users/bob/works", Ao3Urls.authorWorks(AuthorLink("Bob", "bob", "Bob")).toString())
        assertEquals(
            "https://archiveofourown.org/users/author65/pseuds/pseud1/works",
            Ao3Urls.authorWorks(AuthorLink("pseud1 (author65)", "author65", "pseud1")).toString(),
        )
        assertEquals("/users/bob/pseuds/Bob%20Two/works", Ao3Urls.authorWorks(AuthorLink("x", "bob", "Bob Two")).encodedPath)
    }

    @Test fun `series url`() {
        assertEquals("https://archiveofourown.org/series/5", Ao3Urls.series(5).toString())
    }

    @Test fun `page parameter`() {
        val u = "https://archiveofourown.org/series/5".toHttpUrl()
        assertEquals("https://archiveofourown.org/series/5", Ao3Urls.page(u, 1).toString())
        assertEquals("https://archiveofourown.org/series/5?page=3", Ao3Urls.page(u, 3).toString())
        assertEquals("https://archiveofourown.org/series/5?page=2", Ao3Urls.page(Ao3Urls.page(u, 3), 2).toString())
    }

    @Test fun `work list urls are normalized`() {
        assertEquals("https://archiveofourown.org/series/5", list("https://archiveofourown.org/series/5"))
        assertEquals("https://archiveofourown.org/series/5", list("https://archiveofourown.org/series/5?page=2#x"))
        assertEquals("https://archiveofourown.org/users/bob/works", list("https://archiveofourown.org/users/bob"))
        assertEquals("https://archiveofourown.org/users/bob/works", list("https://archiveofourown.org/users/bob/works"))
        assertEquals("https://archiveofourown.org/users/bob/pseuds/B/works", list("https://archiveofourown.org/users/bob/pseuds/B"))
        assertEquals("https://archiveofourown.org/users/bob/pseuds/B/works", list("https://archiveofourown.org/users/bob/pseuds/B/works"))
    }

    @Test fun `anything else is rejected`() {
        assertNull(list("http://archiveofourown.org/series/5"))
        assertNull(list("https://archiveofourown.org:8443/series/5"))
        assertNull(list("https://evil.org/series/5"))
        assertNull(list("https://archiveofourown.org.evil.org/series/5"))
        assertNull(list("https://u:p@archiveofourown.org/series/5"))
        assertNull(list("https://archiveofourown.org/works/5"))
        assertNull(list("https://archiveofourown.org/series/abc"))
        assertNull(list("https://archiveofourown.org/series/5/bookmarks"))
        assertNull(list("https://archiveofourown.org/users/bob/bookmarks"))
        assertNull(list("https://archiveofourown.org/users/orphan_account"))
        assertNull(list("https://archiveofourown.org/users/orphan_account/pseuds/x/works"))
        assertNull(list("https://archiveofourown.org/users/bob/pseuds"))
        assertNull(list("https://archiveofourown.org/users/"))
        assertNull(Ao3Urls.workListOrNull("javascript:alert(1)"))
        assertNull(Ao3Urls.workListOrNull("not a url"))
        assertNull(Ao3Urls.workListOrNull(null))
        assertEquals("https://archiveofourown.org/series/5", Ao3Urls.workListOrNull("https://archiveofourown.org/series/5")?.toString())
    }

    @Test fun `single trailing slash is tolerated`() {
        assertEquals("https://archiveofourown.org/series/5", list("https://archiveofourown.org/series/5/"))
        assertEquals("https://archiveofourown.org/users/bob/works", list("https://archiveofourown.org/users/bob/"))
        assertEquals("https://archiveofourown.org/users/bob/works", list("https://archiveofourown.org/users/bob/works/"))
        assertNull(list("https://archiveofourown.org/series/5//"))
    }
}
