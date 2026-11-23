package io.github.joshitup.lantern.ui.reader

import io.github.joshitup.lantern.ao3.model.Block
import io.github.joshitup.lantern.ao3.model.ChapterContent
import io.github.joshitup.lantern.ao3.model.ChapterRef
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderLayoutTest {
    private fun t(s: String) = Block.Text(s, emptyList())

    private fun content(
        number: Int,
        title: String = "",
        summary: List<Block> = emptyList(),
        begin: List<Block> = emptyList(),
        body: List<Block> = emptyList(),
        end: List<Block> = emptyList(),
    ) = ChapterContent(1L, ChapterRef(10L + number, number, title), "Synthetic Work", listOf("Author"), begin, body, end, summary)

    @Test fun noSplits_identity() {
        val c = content(1, summary = listOf(t("s")), body = listOf(t("a"), t("b"), t("c")))
        // work-title, summary, header-rule, a, b(4)
        assertEquals(4 to 37, legacyRestore(c, 4, 37))
    }

    @Test fun afterSplitBlock_shiftsExactly() {
        val c = content(2, body = listOf(t("a\n\nb\n\nc"), t("d")))
        assertEquals(3 to 20, legacyRestore(c, 1, 20))
    }

    @Test fun insideSplitBlock_startOfFirstPiece() {
        val c = content(2, body = listOf(t("a\n\nb\n\nc"), t("d")))
        assertEquals(0 to 0, legacyRestore(c, 0, 900))
    }

    @Test fun headerAndTitleCounted() {
        val c = content(1, title = "T", summary = listOf(t("s1\n\ns2")), body = listOf(t("x")))
        // Old: work-title 0, summary 1, header-rule 2, chapter-title 3, body 4, footer 5.
        assertEquals(5 to 12, legacyRestore(c, 4, 12))
        assertEquals(1 to 0, legacyRestore(c, 1, 40))
    }

    @Test fun noHeaderNoTitle_mapsFromBeginNotes() {
        val c = content(3, begin = listOf(t("n1\n\nn2")), body = listOf(t("x")))
        // Old: begin note 0, begin-rule 1, body 2, footer 3.
        assertEquals(3 to 8, legacyRestore(c, 2, 8))
    }

    @Test fun endNotesCounted() {
        val c = content(2, body = listOf(t("x")), end = listOf(t("e1\n\ne2"), t("e3")))
        // Old: body 0, end-rule 1, e1/e2 2, e3 3, footer 4.
        assertEquals(4 to 6, legacyRestore(c, 3, 6))
        assertEquals(5 to 0, legacyRestore(c, 4, 0))
    }

    @Test fun indexPastEnd_clampsToLastItem() {
        val c = content(2, body = listOf(t("a\n\nb"), t("d")))
        // New items: a, b, d, footer.
        assertEquals(3 to 0, legacyRestore(c, 99, 50))
    }

    @Test fun splitForReader_splitsAllFourSections() {
        val c = content(1, summary = listOf(t("a\n\nb")), begin = listOf(t("a\n\nb")), body = listOf(t("a\n\nb")), end = listOf(t("a\n\nb")))
        val s = c.splitForReader()
        val pair = listOf(t("a"), t("b"))
        assertEquals(pair, s.summary)
        assertEquals(pair, s.beginNotes)
        assertEquals(pair, s.body)
        assertEquals(pair, s.endNotes)
        assertEquals(c.copy(summary = pair, beginNotes = pair, body = pair, endNotes = pair), s)
    }
}
