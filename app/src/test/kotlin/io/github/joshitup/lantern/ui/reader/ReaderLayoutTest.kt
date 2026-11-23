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

/** Placing a legacy offset inside a block that now splits, against a fake text layout. */
class InBlockRestoreTest {
    /** Every line is 10 px tall; [starts] is each line's first character. */
    private class Lines(private val starts: List<Int>) : LineLookup {
        override fun lineAt(y: Int) = (y / 10).coerceIn(0, starts.lastIndex)
        override fun lineTop(line: Int) = line * 10
        override fun lineBottom(line: Int) = line * 10 + 10
        override fun lineStart(line: Int) = starts[line]
        override fun lineOf(offset: Int) = starts.indexOfLast { it <= offset }
    }

    // "aaaa bbbb\n\ncccc\n\ndddd": pieces at 0..8, 11..14, 17..20; "aaaa bbbb" wraps onto two lines.
    // Lines: 0 "aaaa " (0), 1 "bbbb" (5), 2 blank (10), 3 "cccc" (11), 4 blank (16), 5 "dddd" (17).
    private val lines = Lines(listOf(0, 5, 10, 11, 16, 17))
    private val pieces = listOf(0..8, 11..14, 17..20)
    private fun at(offsetPx: Int) =
        InBlockRestore(firstItem = 3, block = Block.Text("x", emptyList()), pieces = pieces, offsetPx = offsetPx, note = false)

    @Test fun firstPiece_keepsOffsetWithin() = assertEquals(3 to 14, at(14).place(lines))

    @Test fun laterPiece_offsetFromItsOwnTop() = assertEquals(4 to 5, at(35).place(lines))

    @Test fun lastPiece() = assertEquals(5 to 2, at(52).place(lines))

    @Test fun onABlankLine_nextPieceTop() = assertEquals(4 to 0, at(23).place(lines))

    @Test fun pastTheEnd_staysInLastPiece() = assertEquals(5 to 9, at(400).place(lines))
}

class LegacyInBlockTest {
    private fun t(s: String) = Block.Text(s, emptyList())
    private fun content(begin: List<Block> = emptyList(), body: List<Block>) =
        ChapterContent(1L, ChapterRef(12L, 2, "T"), "Synthetic Work", listOf("Author"), begin, body, emptyList(), emptyList())

    @Test fun insideSplitBodyBlock() {
        val block = t("a\n\nb")
        // Old: title 0, "x" 1, "a/b" 2, footer 3.
        val r = legacyInBlock(content(body = listOf(t("x"), block)), 2, 300)!!
        assertEquals(2, r.firstItem)
        assertEquals(block, r.block)
        assertEquals(listOf(0..0, 3..3), r.pieces)
        assertEquals(300, r.offsetPx)
        assertEquals(false, r.note)
    }

    @Test fun insideSplitNote_isNote() {
        val r = legacyInBlock(content(begin = listOf(t("n\n\nm")), body = listOf(t("x"))), 1, 10)!!
        assertEquals(1, r.firstItem)
        assertEquals(true, r.note)
    }

    @Test fun unsplitItem_null() {
        assertEquals(null, legacyInBlock(content(body = listOf(t("x"), t("a\n\nb"))), 1, 30))
    }

    @Test fun pastEnd_null() {
        assertEquals(null, legacyInBlock(content(body = listOf(t("a\n\nb"))), 99, 30))
    }
}
