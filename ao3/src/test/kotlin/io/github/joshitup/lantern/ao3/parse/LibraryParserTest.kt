package io.github.joshitup.lantern.ao3.parse

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class LibraryParserTest {
    private fun res(n: String) = javaClass.getResource("/fixtures/real_signed_in/$n")!!.readText()
    private val base = "https://archiveofourown.org/users/owner/subscriptions".toHttpUrl()

    @Test fun `history page 1`() {
        val p = LibraryParser.readings(res("readings_p1.html"), 1)
        assertEquals(5, p.works.size)
        assertTrue(p.hasNext)
        assertEquals(1, p.page)
        assertEquals(1000107L, p.works[0].workId)
        assertEquals("Work Title 8", p.works[0].title)
        val e = p.readings.getValue(1000107L)
        assertEquals("20 Jun 2020", e.lastVisited)
        assertEquals(7, e.visits)
        assertFalse(e.updateAvailable)
        assertEquals(1, p.readings.getValue(1000119L).visits)
        val u = p.readings.getValue(1000109L)
        assertTrue(u.updateAvailable)
        assertEquals(5, u.visits)
    }

    @Test fun `history page 2 skips deleted works`() {
        val p = LibraryParser.readings(res("readings_p2.html"), 2)
        assertEquals(3, p.works.size)
        assertTrue(p.hasNext)
        val e = p.readings.getValue(1000123L)
        assertEquals("14 Jun 2020", e.lastVisited)
        assertTrue(e.updateAvailable)
        assertEquals(15, e.visits)
    }

    @Test fun `marked for later`() {
        val p = LibraryParser.readings(res("marked_for_later.html"), 1)
        assertEquals(1, p.works.size)
        assertFalse(p.hasNext)
        assertEquals(1000115L, p.works[0].workId)
        assertEquals("Work Title 16", p.works[0].title)
        assertEquals("25 Jan 2020", p.readings.getValue(1000115L).lastVisited)
    }

    @Test fun `real bookmarks skip series and mystery`() {
        val p = LibraryParser.bookmarks(res("bookmarks.html"), 1)
        assertEquals(3, p.works.size)
        assertTrue(p.hasNext)
        assertEquals(1000112L, p.works[0].workId)
        assertEquals("Work Title 13", p.works[0].title)
        assertTrue(p.works[0].summaryHtml!!.startsWith("<p>In ut minim minim et deserunt"))
        val b = p.bookmarks.getValue(1000112L)
        assertTrue(b.isPrivate)
        assertFalse(b.isRec)
        assertNull(b.notesHtml)
        assertTrue(b.tags.isEmpty())
        assertTrue(p.works.any { it.workId == 1000102L })
    }

    // unverified (hand-authored from AO3 source; the real captures have no notes/tags/rec)
    @Test fun `bookmark notes and tags are separate from work summary`() {
        val html = """<ol class="bookmark index group"><li id="bookmark_9" class="bookmark blurb group work-5 user-1" role="article">
          <p class="status"><span class="rec" title="Rec"><span class="text">Rec</span></span></p>
          <div class="header module"><h4 class="heading"><a href="/works/5">T</a> by <a rel="author" href="/users/a/pseuds/a">a</a></h4></div>
          <blockquote class="userstuff summary"><p>WORK SUMMARY</p></blockquote>
          <div class="user module group"><h5 class="byline heading">Bookmarked by x</h5>
            <p class="datetime">1 Jan 2026</p>
            <p class="status"><span class="private"></span></p>
            <ul class="meta tags commas"><li><a class="tag" href="/t/1">to read</a></li><li><a class="tag" href="/t/2">angst</a></li></ul>
            <blockquote class="userstuff notes"><p>MY NOTES</p></blockquote></div></li></ol>"""
        val p = LibraryParser.bookmarks(html, 1)
        assertEquals("<p>WORK SUMMARY</p>", p.works.single().summaryHtml)
        assertTrue(p.works.single().freeforms.isEmpty())
        val b = p.bookmarks.getValue(5L)
        assertEquals("<p>MY NOTES</p>", b.notesHtml)
        assertEquals(listOf("to read", "angst"), b.tags)
        assertTrue(b.isRec)
        assertTrue(b.isPrivate)
    }

    @Test fun `summary-like blockquote inside bookmarker module is not the work summary`() {
        val html = """<ol><li id="bookmark_9" class="bookmark blurb work-5"><div class="header module"><h4 class="heading"><a href="/works/5">T</a></h4></div>
          <div class="user module group"><blockquote class="userstuff summary"><p>X</p></blockquote></div></li></ol>"""
        assertNull(LibraryParser.bookmarks(html, 1).works.single().summaryHtml)
    }

    @Test fun `real subscriptions are series rows`() {
        val p = LibraryParser.subscriptions(res("subscriptions.html"), 1, base)
        assertEquals(5, p.items.size)
        assertTrue(p.hasNext)
        val s = p.items[0] as Subscription.Series
        assertEquals("Series 2", s.title)
        assertEquals("https://archiveofourown.org/series/4000101", s.url.toString())
        assertTrue(p.items.all { it is Subscription.Series })
    }

    // unverified (hand-authored from the real series row markup)
    @Test fun `work and user subscription rows`() {
        val html = """<dl class="subscription index group">
          <dt><a href="/works/123">Some Work</a> (Work) by <a rel="author" href="/users/bob/pseuds/bob">bob</a></dt><dd></dd>
          <dt><a href="/users/carol">carol</a> (User)</dt><dd></dd>
          <dt><a href="/works/124">Anon Work</a> (Work) by Anonymous</dt><dd></dd></dl>"""
        val p = LibraryParser.subscriptions(html, 2, base)
        assertEquals(2, p.page)
        assertFalse(p.hasNext)
        assertEquals(Subscription.Work(123, "Some Work", "bob"), p.items[0])
        val u = p.items[1] as Subscription.User
        assertEquals("carol", u.title)
        assertEquals("https://archiveofourown.org/users/carol", u.url.toString())
        assertEquals(Subscription.Work(124, "Anon Work", null), p.items[2])
    }

    @Test fun `garbage never throws`() {
        assertTrue(LibraryParser.readings("", 1).works.isEmpty())
        assertTrue(LibraryParser.bookmarks("<html>", 1).works.isEmpty())
        assertTrue(LibraryParser.subscriptions("x", 1, base).items.isEmpty())
    }
}
