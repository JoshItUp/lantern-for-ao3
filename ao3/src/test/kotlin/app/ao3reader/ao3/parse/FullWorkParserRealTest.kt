package app.ao3reader.ao3.parse

import app.ao3reader.ao3.model.Block
import app.ao3reader.ao3.model.ChapterContent
import app.ao3reader.ao3.model.ChapterRef
import org.junit.Assert.*
import org.junit.Test

/** Parser checks against real signed-out full-work pages (trimmed and anonymised). */
class FullWorkParserRealTest {
    private fun res(n: String) = javaClass.getResource("/fixtures/real_signed_out/$n")!!.readText()

    private fun parse(name: String, id: Long): Pair<FullWorkMeta?, List<ChapterContent>> {
        val out = mutableListOf<ChapterContent>()
        val meta = FullWorkParser.parse(res(name), id) { out += it }
        return meta to out
    }

    private val multi by lazy { parse("full_work_multi.html", 1000002) }

    @Test fun multi_emitsFourChaptersInOrder() {
        val (meta, chapters) = multi
        assertEquals(listOf(2000001L, 2000002L, 2000003L, 2000004L), chapters.map { it.ref.chapterId })
        assertEquals(listOf(1, 2, 3, 4), chapters.map { it.ref.number })
        assertTrue(chapters.all { it.ref.title.isNotBlank() })
        assertEquals("Chapter 4: epilogue", chapters[3].ref.title)
        assertEquals(chapters.map { it.ref }, meta!!.chapters)
        assertTrue(chapters.all { it.body.isNotEmpty() })
        assertTrue(chapters.all { it.workId == 1000002L && it.authors == listOf("author1") })
    }

    @Test fun multi_summaryOnlyOnFirstChapter() {
        val chapters = multi.second
        assertTrue(chapters[0].summary.isNotEmpty())
        assertTrue(chapters.drop(1).all { it.summary.isEmpty() })
    }

    @Test fun multi_chapterNotesScopedToTheirChapter() {
        val chapters = multi.second
        // Chapter 2 is the only one with end notes of its own (carrying <b> labels); 3 and 4 have plain ones.
        assertTrue(chapters[0].endNotes.isEmpty())
        assertTrue(chapters[1].endNotes.isNotEmpty())
        assertTrue(chapters[2].endNotes.isNotEmpty())
        // Begin notes: work notes sit before chapter 1 only; later chapters carry just their own notes.
        val workNote = "Aliquip lorem ipsum dolor sit amet"
        fun has(c: ChapterContent) = c.beginNotes.any { it is Block.Text && workNote in it.text }
        assertTrue(has(chapters[0]))
        assertFalse(chapters.drop(1).any(::has))
        assertTrue(chapters.drop(1).all { it.beginNotes.isNotEmpty() })
        val distinct = chapters.map { it.endNotes.toString() }.toSet()
        assertEquals(4, distinct.size)
    }

    @Test fun multi_workEndNotesOnlyOnLastChapter() {
        val chapters = multi.second
        val workNote = "Minim veniam quis nostrud"
        fun has(c: ChapterContent) = c.endNotes.any { it is Block.Text && workNote in it.text }
        assertTrue(has(chapters[3]))
        assertFalse(chapters.dropLast(1).any(::has))
        // The last chapter keeps its own chapter end notes alongside the work notes.
        assertTrue(chapters[3].endNotes.size >= 2)
    }

    @Test fun multi_metaBlurb() {
        val (meta, chapters) = multi
        assertEquals(4, meta!!.blurb.chaptersPosted)
        assertEquals(chapters.size, meta.blurb.chaptersPosted)
        assertEquals(1000002L, meta.blurb.workId)
        assertEquals("Full Work Sample", meta.blurb.title)
    }

    @Test fun single_oneChapterWithoutId() {
        val (meta, chapters) = parse("full_work_single.html", 1000003)
        assertEquals(1, chapters.size)
        val ch = chapters.single()
        assertEquals(ChapterRef(null, 1, "One-Shot Sample"), ch.ref)
        assertEquals(listOf(ch.ref), meta!!.chapters)
        assertTrue(ch.body.isNotEmpty())
        assertEquals(ChapterParser.parse(res("full_work_single.html"), 1000003), ch)
    }
}
