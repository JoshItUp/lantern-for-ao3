package app.ao3reader.ao3.parse

import app.ao3reader.ao3.model.Block
import app.ao3reader.ao3.model.ChapterContent
import org.junit.Assert.*
import org.junit.Test

class FullWorkParserTest {
    private fun res(n: String) = javaClass.getResource("/fixtures/$n")!!.readText()
    private fun texts(b: List<Block>) = b.map { if (it is Block.Text) it.text else it.toString() }

    private fun parse(name: String, id: Long): Pair<FullWorkMeta?, List<ChapterContent>> {
        val out = mutableListOf<ChapterContent>()
        val meta = FullWorkParser.parse(res(name), id) { out += it }
        return meta to out
    }

    @Test fun multi_emitsThreeChaptersInOrder() {
        val (meta, chapters) = parse("full_work_multi.html", 1000000)
        assertEquals(listOf(2001L to 1, 2002L to 2, 2003L to 3), chapters.map { it.ref.chapterId to it.ref.number })
        assertEquals(listOf("Chapter 1: Opening", "Chapter 2: Middle", "Chapter 3: Close"), chapters.map { it.ref.title })
        assertEquals(chapters.map { it.ref }, meta!!.chapters)
        assertEquals(listOf(listOf("One body."), listOf("Two body."), listOf("Three body.")), chapters.map { texts(it.body) })
        assertTrue(chapters.all { it.workTitle == "Three Parts" && it.authors == listOf("owner") && it.workId == 1000000L })
    }

    @Test fun multi_notesScopedPerChapter() {
        val (ch1, ch2, ch3) = parse("full_work_multi.html", 1000000).second
        assertEquals(listOf("Work summary text."), texts(ch1.summary))
        assertTrue(ch2.summary.isEmpty())
        assertTrue(ch3.summary.isEmpty())

        // Work notes, then the chapter summary under its heading, then the chapter's own notes.
        assertEquals(
            listOf(Block.Text("Work begin note.", emptyList()), Block.Heading("Summary", 4),
                Block.Text("Chapter one summary.", emptyList()), Block.Text("Chapter one begin note.", emptyList())),
            ch1.beginNotes,
        )
        assertTrue(ch2.beginNotes.isEmpty())
        assertTrue(ch3.beginNotes.isEmpty())

        // Chapter 2's end notes stay on chapter 2.
        assertEquals(listOf("Chapter two end note."), texts(ch2.endNotes))
        assertFalse("Chapter two end note." in texts(ch1.endNotes))
        assertFalse("Chapter two end note." in texts(ch3.endNotes))

        // Work end notes go on the last chapter only.
        assertEquals(listOf("Work end note."), texts(ch3.endNotes))
        assertTrue(ch1.endNotes.isEmpty())
        assertFalse("Work end note." in texts(ch2.endNotes))
    }

    @Test fun multi_metaBlurb() {
        val meta = parse("full_work_multi.html", 1000000).first!!
        assertEquals(3, meta.blurb.chaptersPosted)
        assertEquals(5, meta.blurb.chaptersTotal)
        assertEquals("Three Parts", meta.blurb.title)
        assertEquals(1000000L, meta.blurb.workId)
    }

    @Test fun single_matchesChapterParser() {
        val (meta, chapters) = parse("full_work_single.html", 1000001)
        val expected = ChapterParser.parse(res("full_work_single.html"), 1000001)
        assertEquals(listOf(expected), chapters)
        assertEquals(listOf(expected.ref), meta!!.chapters)
        assertNull(expected.ref.chapterId)
        assertEquals("Lone Piece", expected.ref.title)
        assertEquals(listOf("Single body one.", "Single body two."), texts(expected.body))
        assertEquals(listOf("Single summary."), texts(expected.summary))
        assertEquals(listOf("Single begin note."), texts(expected.beginNotes))
        assertEquals(listOf("Single end note."), texts(expected.endNotes))
    }

    @Test fun signInPage_returnsNull() {
        var calls = 0
        assertNull(FullWorkParser.parse(res("login_page.html"), 1) { calls++ })
        assertEquals(0, calls)
    }
}
