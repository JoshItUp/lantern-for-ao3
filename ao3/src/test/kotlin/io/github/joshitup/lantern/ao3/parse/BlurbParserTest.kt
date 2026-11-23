package io.github.joshitup.lantern.ao3.parse

import org.junit.Assert.*
import org.junit.Test

class BlurbParserTest {
    private val html = javaClass.getResource("/fixtures/search_results.html")!!.readText()

    @Test fun `parses full blurb`() {
        val page = BlurbParser.parsePage(html, 1)
        assertEquals(3, page.works.size)
        assertEquals(1, page.page)
        val w = page.works[0]
        assertEquals(111L, w.workId)
        assertEquals("Title One", w.title)
        assertEquals(listOf("a1"), w.authors)
        assertEquals(listOf("Fandom One"), w.fandoms)
        assertEquals("Teen And Up Audiences", w.rating)
        assertEquals(listOf("No Archive Warnings Apply"), w.warnings)
        assertEquals(listOf("M/M"), w.categories)
        assertEquals(true, w.complete)
        assertEquals(listOf("A/B"), w.relationships)
        assertEquals(listOf("A", "B"), w.characters)
        assertEquals(listOf("Tag 96", "Tag 280"), w.freeforms)
        assertTrue(w.summaryHtml!!.contains("<em>summary</em>"))
        assertEquals("English", w.language)
        assertEquals(84213, w.words)
        assertEquals(12, w.chaptersPosted)
        assertEquals(12, w.chaptersTotal)
        assertEquals(5120, w.kudos)
        assertEquals(301, w.comments)
        assertEquals(800, w.bookmarks)
        assertEquals(90001, w.hits)
        assertEquals("12 Nov 2026", w.updated)
    }

    @Test fun `in progress total chapters null`() {
        val w = BlurbParser.parsePage(html, 1).works[1]
        assertEquals(listOf("Fandom Two", "Fandom Three"), w.fandoms)
        assertEquals(3, w.chaptersPosted)
        assertNull(w.chaptersTotal)
        assertEquals(false, w.complete)
    }

    @Test fun `anonymous and sparse blurb`() {
        val w = BlurbParser.parsePage(html, 1).works[2]
        assertEquals(333L, w.workId)
        assertEquals(listOf("Anonymous"), w.authors)
        assertNull(w.summaryHtml)
        assertNull(w.comments)
        assertNull(w.bookmarks)
        assertTrue(w.relationships.isEmpty())
        assertTrue(w.characters.isEmpty())
        assertEquals(1, w.chaptersTotal)
    }

    @Test fun `hasNext true when next link present`() {
        assertTrue(BlurbParser.parsePage(html, 1).hasNext)
        val stripped = html.replace(Regex("<ol class=\"pagination actions\".*?</ol>", RegexOption.DOT_MATCHES_ALL), "")
        assertFalse(BlurbParser.parsePage(stripped, 1).hasNext)
    }

    @Test fun `empty listing`() {
        val p = BlurbParser.parsePage("<html><body><div id=\"main\"><ol class=\"work index group\"></ol></div></body></html>", 2)
        assertTrue(p.works.isEmpty())
        assertFalse(p.hasNext)
        assertEquals(2, p.page)
    }

    // --- Real AO3 captures (trimmed to 5 blurbs, pagination kept) ---
    private val real = javaClass.getResource("/fixtures/search_results_real.html")!!.readText()
    private val realLast = javaClass.getResource("/fixtures/search_results_last_real.html")!!.readText()

    @Test fun `real search page parses every blurb`() {
        val page = BlurbParser.parsePage(real, 1)
        assertEquals(listOf(1000128L, 1000124L, 1000126L, 1000117L, 1000101L), page.works.map { it.workId })
        assertTrue(page.hasNext)
        assertTrue(page.works.all { it.title.isNotEmpty() && it.authors.isNotEmpty() && it.summaryHtml != null })
        assertTrue(page.works.all { it.fandoms == listOf("Fandom A") && it.language == "English" })
    }

    @Test fun `real blurb fields`() {
        val w = BlurbParser.parsePage(real, 1).works[0]
        assertEquals("Work Title 29", w.title)
        assertEquals(listOf("author57"), w.authors)
        assertEquals("Explicit", w.rating)
        assertEquals(listOf("Creator Chose Not To Use Archive Warnings"), w.warnings)
        assertEquals(listOf("F/F", "F/M", "Multi"), w.categories)   // comma-joined title split
        assertEquals(true, w.complete)
        assertEquals(8, w.relationships.size)
        assertEquals("Character P/Character B", w.relationships[0])
        assertEquals(listOf("Character A", "Character P", "Character BA", "Character B"), w.characters)
        assertEquals(16, w.freeforms.size)
        assertEquals("Tag 112", w.freeforms.last())
        assertTrue(w.summaryHtml!!.startsWith("<p>Culpa velit sit laborum et minim ex qui."))
        assertEquals(74000, w.words)
        assertEquals(12, w.chaptersPosted)   // "<a>12</a>/12"
        assertEquals(12, w.chaptersTotal)
        assertEquals(62, w.kudos)
        assertEquals(3700, w.hits)
        assertEquals(20, w.bookmarks)
        assertEquals(6, w.comments)
        assertEquals("29 Jun 2020", w.updated)
    }

    @Test fun `real blurb with not rated and summary line break`() {
        val w = BlurbParser.parsePage(real, 1).works[4]
        assertEquals("Work Title 2", w.title)
        assertEquals(listOf("author74"), w.authors)
        assertEquals("Not Rated", w.rating)
        assertEquals(listOf("F/M"), w.categories)
        assertEquals(listOf("Tag 398", "Tag 399", "Tag 400"), w.freeforms)
        assertEquals(10, w.chaptersPosted)
        assertEquals(10, w.chaptersTotal)
        assertEquals(19000, w.hits)
        assertTrue(w.summaryHtml!!.contains("<br>"))
    }

    @Test fun `real last page has no next`() {
        val page = BlurbParser.parsePage(realLast, 4)
        assertFalse(page.hasNext)
        assertEquals(1, page.works.size)
        val w = page.works[0]
        assertEquals(1000105L, w.workId)
        assertEquals("Work Title 6", w.title)
        assertEquals(listOf("author71"), w.authors)
        assertEquals(listOf("F/M", "Multi"), w.categories)
        assertEquals(98000, w.words)
        assertEquals(20, w.kudos)
        assertEquals(1, w.comments)
        assertEquals("01 Jan 2020", w.updated)
    }
}
