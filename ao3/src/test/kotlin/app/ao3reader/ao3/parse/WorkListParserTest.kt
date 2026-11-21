package app.ao3reader.ao3.parse

import app.ao3reader.ao3.model.AuthorLink
import app.ao3reader.ao3.model.SeriesLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Author and series pages (real signed-in captures) and the links parsed into every blurb. */
class WorkListParserTest {
    private fun res(n: String) = javaClass.getResource("/fixtures/real_signed_in/$n")!!.readText()

    @Test fun `user works page parses blurbs with author and series links`() {
        val p = BlurbParser.parsePage(res("user_works_p1.html"), 1)
        assertEquals(5, p.works.size)
        assertFalse(p.hasNext)
        val first = p.works[0]
        assertEquals(1000106L, first.workId)
        assertEquals(listOf("author59"), first.authors)
        assertEquals(listOf(AuthorLink("author59", "author59", "author59")), first.authorLinks)
        assertTrue(first.series.isEmpty())
        val inSeries = p.works.single { it.workId == 1000103L }
        assertEquals(listOf(SeriesLink(4000102L, "Series 3", 1)), inSeries.series)
    }

    @Test fun `page past the end is empty`() {
        val p = BlurbParser.parsePage(res("user_works_p2.html"), 2)
        assertTrue(p.works.isEmpty())
        assertFalse(p.hasNext)
    }

    @Test fun `small user works page`() {
        val p = BlurbParser.parsePage(res("user_works_small.html"), 1)
        assertEquals(listOf(1000119L, 1000104L), p.works.map { it.workId })
        assertEquals(AuthorLink("author60", "author60", "author60"), p.works[0].authorLinks.single())
    }

    @Test fun `series page lists works in series order with parts`() {
        val p = BlurbParser.parsePage(res("series_a.html"), 1)
        assertEquals(listOf(1000100L, 1000113L), p.works.map { it.workId })
        assertEquals(listOf(1, 2), p.works.map { w -> w.series.single { it.id == 4000101L }.part })
        assertEquals("Series 2", p.works[0].series.single().title)
        assertFalse(p.hasNext)
        // Co-authored by two pseuds of one user; a pseud differing from the user name keeps both parts.
        assertEquals(
            listOf(AuthorLink("pseud1 (author65)", "author65", "pseud1"), AuthorLink("author65", "author65", "author65")),
            p.works[1].authorLinks,
        )
        assertEquals(listOf("pseud1 (author65)", "author65"), p.works[1].authors)
        val b = BlurbParser.parsePage(res("series_b.html"), 1)
        assertEquals(listOf(1000108L, 1000110L), b.works.map { it.workId })
        assertEquals(listOf(1, 2), b.works.map { w -> w.series.single().part })
    }

    @Test fun `series title from the page heading`() {
        assertEquals("Series 2", WorkListParser.seriesTitle(res("series_a.html")))
        assertEquals("Series 6", WorkListParser.seriesTitle(res("series_b.html")))
        assertNull(WorkListParser.seriesTitle(res("user_works_p1.html")))
    }

    @Test fun `anonymous has no author link`() {
        val w = LibraryParser.bookmarks(res("bookmarks.html"), 1).works.single { it.workId == 1000121L }
        assertEquals(listOf("Anonymous"), w.authors)
        assertTrue(w.authorLinks.isEmpty())
    }

    @Test fun `orphan account and odd hrefs are not links`() {
        val html = """<ol><li id="work_7" class="work blurb group"><h4 class="heading"><a href="/works/7">T</a> by
            <a rel="author" href="/users/orphan_account/pseuds/someone">someone</a>,
            <a rel="author" href="/users/bob/pseuds/Bob%20Two">Bob Two (bob)</a>,
            <a rel="author" href="https://evil.example/users/x/pseuds/x">x</a></h4>
            <ul class="series"><li>Part <strong>3</strong> of <a href="/series/99">S</a></li>
            <li><a href="/series/abc">Bad</a></li></ul></li></ol>"""
        val w = BlurbParser.parsePage(html, 1).works.single()
        assertEquals(listOf("someone", "Bob Two (bob)", "x"), w.authors)
        assertEquals(listOf(AuthorLink("Bob Two (bob)", "bob", "Bob Two")), w.authorLinks)
        assertEquals(listOf(SeriesLink(99L, "S", 3)), w.series)
    }
}
