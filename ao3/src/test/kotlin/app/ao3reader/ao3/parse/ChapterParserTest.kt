package app.ao3reader.ao3.parse

import app.ao3reader.ao3.model.AuthorLink
import app.ao3reader.ao3.model.Block
import app.ao3reader.ao3.model.SeriesLink
import app.ao3reader.ao3.model.ChapterRef
import app.ao3reader.ao3.model.Span
import org.junit.Assert.*
import org.junit.Test

class ChapterParserTest {
    private fun res(n: String) = javaClass.getResource("/fixtures/$n")!!.readText()
    private fun texts(b: List<Block>) = b.map { if (it is Block.Text) it.text else it.toString() }

    @Test fun `multi chapter`() {
        val c = ChapterParser.parse(res("chapter_multi.html"), 111)
        assertEquals(111L, c.workId)
        assertEquals(ChapterRef(9004, 4, "Chapter 4: Name"), c.ref)
        assertEquals("Big Story", c.workTitle)
        assertEquals(listOf("AuthorOne"), c.authors)
        assertTrue(texts(c.body).none { it.contains("Chapter Text") })
        assertEquals("First paragraph with bold text.", texts(c.body)[0])
        assertEquals("Second paragraph\nafter a break.", texts(c.body)[1])
        assertEquals(Block.Rule, c.body[2])
        assertTrue((c.body[3] as Block.Text).center)
        assertEquals(4, c.body.size)
        // The chapter summary leads the begin notes, under a "Summary" heading; the work summary is chapter 1 only.
        assertEquals(Block.Heading("Summary", 4), c.beginNotes[0])
        assertEquals(listOf("chap summary", "Begin note one.", "Begin note two."), texts(c.beginNotes.drop(1)))
        assertEquals(listOf("End note here."), texts(c.endNotes))
        assertTrue(c.summary.isEmpty())   // work summary only for chapter 1
    }

    private fun firstChapter(chapterSummary: String) = """<html><body><div id="workskin">
        <div class="preface group"><h2 class="title">W</h2>
        <div class="summary module"><blockquote class="userstuff"><p>Work summary.</p></blockquote></div>
        <div class="notes module"><blockquote class="userstuff"><p>Work note.</p></blockquote></div></div>
        <div id="chapters"><div class="chapter" id="chapter-1"><div class="chapter preface group">
        <h3 class="title"><a href="/works/1/chapters/5">Chapter 1</a></h3>
        <div id="summary" class="summary module"><blockquote class="userstuff"><p>$chapterSummary</p></blockquote></div>
        <div id="notes" class="notes module"><blockquote class="userstuff"><p>Chapter note.</p></blockquote></div></div>
        <div class="userstuff module"><p>Body.</p></div></div></div></div></body></html>"""

    @Test fun `chapter 1 summary equal to the work summary is not repeated`() {
        val c = ChapterParser.parse(firstChapter("Work summary."), 1)
        assertEquals(listOf("Work summary."), texts(c.summary))
        assertEquals(listOf("Work note.", "Chapter note."), texts(c.beginNotes))
    }

    @Test fun `chapter 1 summary distinct from the work summary sits between work and chapter notes`() {
        val c = ChapterParser.parse(firstChapter("Chapter one summary."), 1)
        assertEquals(listOf("Work summary."), texts(c.summary))
        assertEquals(
            listOf(Block.Text("Work note.", emptyList()), Block.Heading("Summary", 4),
                Block.Text("Chapter one summary.", emptyList()), Block.Text("Chapter note.", emptyList())),
            c.beginNotes,
        )
    }

    @Test fun `parseOrNull parses a chapter page once and rejects other pages`() {
        assertEquals(ChapterParser.parse(res("chapter_multi.html"), 111), ChapterParser.parseOrNull(res("chapter_multi.html"), 111))
        assertNull(ChapterParser.parseOrNull(res("navigate_real.html"), 1))
        assertNull(ChapterParser.parseOrNull(res("search_results_real.html"), 1))
        assertNull(ChapterParser.parseOrNull("<html><body>Just a moment</body></html>", 1))
        // A chapter container with an empty body is not usable either.
        assertNull(ChapterParser.parseOrNull("""<div id="chapters"><div class="userstuff module"> </div></div>""", 1))
    }

    @Test fun `single chapter work`() {
        val c = ChapterParser.parse(res("work_single.html"), 222)
        assertEquals(ChapterRef(null, 1, "One Shot"), c.ref)
        assertEquals(listOf("Only paragraph one.", "Only paragraph two."), texts(c.body))
        assertEquals(listOf("Solo"), c.authors)
        assertTrue(c.beginNotes.isEmpty())
        assertEquals(listOf("Short summary."), texts(c.summary))
    }

    @Test fun `chapter 1 concatenates work notes then chapter notes`() {
        val c = ChapterParser.parse(res("chapter_first_notes.html"), 1)
        assertEquals(listOf("Work note.", "Chapter note."), texts(c.beginNotes))
    }

    @Test fun `last chapter keeps chapter end notes then work end notes`() {
        val c = ChapterParser.parse(res("chapter_last_endnotes.html"), 1)
        assertEquals(listOf("Chapter end.", "Work end."), texts(c.endNotes))
        assertEquals(listOf("Body."), texts(c.body))
    }

    @Test fun `garbage html does not throw`() {
        val c = ChapterParser.parse("<html></html>", 1)
        assertTrue(c.body.isEmpty())
    }

    // --- Real AO3 captures (bodies trimmed to 5 paragraphs) ---
    private fun Block.text() = (this as Block.Text).text

    @Test fun `real first chapter`() {
        val html = res("chapter_first_real.html")
        assertTrue(ChapterParser.looksLikeChapterPage(html))
        val c = ChapterParser.parse(html, 1000125)
        assertEquals(ChapterRef(2000120, 1, "Chapter 1: Chapter Title 6"), c.ref)
        assertEquals("Work Title 26", c.workTitle)
        assertEquals(listOf("author2"), c.authors)
        assertEquals(5, c.body.size)
        assertTrue(c.body[0].text().startsWith("Minim enim mollit, occaecat"))
        assertTrue(texts(c.body).none { it.contains("Chapter Text") || it.contains("AO3 is now up") })
        // chapter 1: the work's begin notes; no chapter or work end notes on this page
        assertEquals(1, c.beginNotes.size)
        assertTrue(c.beginNotes[0].text().startsWith("Do'ut sint pretium ex elit est mollit"))
        assertTrue(c.endNotes.isEmpty())
        assertEquals(1, c.summary.size)
        assertTrue(c.summary[0].text().startsWith("Enim id Sit non officia Bibendum"))
    }

    @Test fun `real later chapter`() {
        val html = res("chapter_later_real.html")
        assertTrue(ChapterParser.looksLikeChapterPage(html))
        val c = ChapterParser.parse(html, 1000125)
        assertEquals(ChapterRef(2000124, 4, "Chapter 4: Chapter Title 10"), c.ref)
        assertEquals("Work Title 26", c.workTitle)
        assertEquals(listOf("author2"), c.authors)
        assertEquals(5, c.body.size)
        assertTrue(c.body[0].text().startsWith("Est nisi ullamco ex porttitor"))
        assertEquals("Laboris mollit sint et sunt excepteur. \u201cQui\u2019id nostrud ut pariatur dolore sint Cupidatat Minim?\u201d", c.body[4].text())
        assertTrue(texts(c.body).none { it.contains("Chapter Text") })
        // The begin "notes" module only holds "(See the end of the chapter for notes.)" outside a blockquote,
        // so the begin notes are just the chapter summary under its heading. No work summary past chapter 1.
        assertEquals(2, c.beginNotes.size)
        assertEquals(Block.Heading("Summary", 4), c.beginNotes[0])
        assertTrue(c.beginNotes[1].text().startsWith("Lorem amet ea condimentum laboris occaecat quis Fugiat"))
        assertTrue(c.summary.isEmpty())
        assertEquals(4, c.endNotes.size)
        assertEquals("Ex sit irure ipsum ad nisi sed-", c.endNotes[0].text())
        assertTrue(c.endNotes[3].text().startsWith("Non ipsum facilisis duis'ex tempor ea"))
    }

    @Test fun `real single chapter work`() {
        val html = res("work_single_real.html")
        assertTrue(ChapterParser.looksLikeChapterPage(html))
        val c = ChapterParser.parse(html, 1000127)
        // Fetched as /works/1000127/chapters/2000159: a 1/1 work still has a chapter preface and id, but no #selected_id
        assertEquals(ChapterRef(2000159, 1, "Chapter 1"), c.ref)
        assertEquals("Work Title 28", c.workTitle)
        assertEquals(listOf("author53"), c.authors)
        assertEquals(5, c.body.size)
        assertEquals(Block.Text("Et adipiscing proident. In sed duis do non esse esse exercitation.", listOf(Span.Italic(14, 22))), c.body[1])
        assertEquals("Ipsum est ea sunt ea sit dolor quis ea elit fugiat.", c.body[4].text())
        assertEquals(2, c.beginNotes.size)
        assertTrue(c.beginNotes[0].text().startsWith("Quis ad veniam Dolore ET qui-quis"))
        assertTrue(c.endNotes.isEmpty())
        assertEquals(4, c.summary.size)
        assertTrue(c.summary[0].text().startsWith("Officia (sed dolore)."))
    }

    @Test fun `real chapter ids agree with the navigate page`() {
        val refs = NavigateParser.parse(res("navigate_real.html"))
        assertEquals(refs[0].chapterId, ChapterParser.parse(res("chapter_first_real.html"), 1000125).ref.chapterId)
        val later = ChapterParser.parse(res("chapter_later_real.html"), 1000125).ref
        assertEquals(refs[3].chapterId, later.chapterId)
        assertEquals(refs[3].number, later.number)
        assertEquals(NavigateParser.parse(res("navigate_single_real.html")).single().chapterId,
            ChapterParser.parse(res("work_single_real.html"), 1000127).ref.chapterId)
    }

    @Test fun `navigate and search pages do not look like chapter pages`() {
        assertFalse(ChapterParser.looksLikeChapterPage(res("navigate_real.html")))
        assertFalse(ChapterParser.looksLikeChapterPage(res("search_results_real.html")))
    }

    @Test fun `byline author links on a real page`() {
        val c = ChapterParser.parse(res("chapter_first_real.html"), 1000125)
        assertEquals(listOf(AuthorLink("author2", "author2", "author2")), c.authorLinks)
        assertTrue(c.series.isEmpty())
    }

    @Test fun `work meta series and pseud byline`() {
        val html = """<html><body><div class="wrapper"><dl class="work meta group">
            <dt class="series">Series:</dt><dd class="series">
            <span class="series"><a class="previous" href="/works/1">← Previous Work</a>
            <span class="position">Part 2 of <a href="/series/4000101">Series 2</a></span>
            <a class="next" href="/works/3">Next Work →</a></span>
            <span class="series"><span class="position">Part 10 of <a href="/series/42">Other</a></span></span>
            </dd></dl></div>
            <div id="workskin"><div class="preface group"><h2 class="title">W</h2>
            <h3 class="byline heading"><a rel="author" href="/users/author65/pseuds/pseud1">pseud1 (author65)</a>,
            <a rel="author" href="/users/orphan_account/pseuds/gone">gone</a></h3></div>
            <div id="chapters"><div class="userstuff"><p>Body.</p></div></div></div></body></html>"""
        val c = ChapterParser.parse(html, 2)
        assertEquals(listOf("pseud1 (author65)", "gone"), c.authors)
        assertEquals(listOf(AuthorLink("pseud1 (author65)", "author65", "pseud1")), c.authorLinks)
        assertEquals(listOf(SeriesLink(4000101L, "Series 2", 2), SeriesLink(42L, "Other", 10)), c.series)
    }
}
