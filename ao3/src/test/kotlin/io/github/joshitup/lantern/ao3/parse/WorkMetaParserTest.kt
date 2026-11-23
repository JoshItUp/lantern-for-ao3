package io.github.joshitup.lantern.ao3.parse

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class WorkMetaParserTest {
    private val html = javaClass.getResource("/fixtures/real_signed_in/chapter_plain.html")!!.readText()

    @Test fun blurbFromWorkPage() {
        val b = WorkMetaParser.blurb(Jsoup.parse(html, Ao3Urls.BASE_URI), 5)
        assertEquals(5L, b.workId)
        assertTrue(b.title.isNotBlank())
        assertTrue(b.authors.isNotEmpty())
        assertEquals(listOf("Fandom A"), b.fandoms)
        assertEquals("Explicit", b.rating)
        assertEquals(listOf("F/F", "F/M", "Multi"), b.categories)
        assertEquals("English", b.language)
        assertTrue(b.words!! > 0)
        assertEquals(74000, b.words)
        assertEquals(12, b.chaptersPosted)
        assertEquals(12, b.chaptersTotal)
        assertEquals(true, b.complete)
        assertEquals(62, b.kudos)
        assertTrue(b.relationships.isNotEmpty() && b.characters.isNotEmpty() && b.freeforms.isNotEmpty())
    }

    @Test fun postedChaptersFromFixture() {
        assertEquals(12, WorkMetaParser.postedChapters(html))
    }

    @Test fun postedChaptersAbsent() {
        assertNull(WorkMetaParser.postedChapters("<html></html>"))
    }

    @Test fun postedChaptersUnknownTotal() {
        assertEquals(3, WorkMetaParser.postedChapters("""<dl class="stats"><dd class="chapters">3/?</dd></dl>"""))
    }
}
