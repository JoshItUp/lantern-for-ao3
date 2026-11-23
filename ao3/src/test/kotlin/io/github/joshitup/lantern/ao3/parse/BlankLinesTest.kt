package io.github.joshitup.lantern.ao3.parse

import io.github.joshitup.lantern.ao3.model.Block
import io.github.joshitup.lantern.ao3.model.Span
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

class BlankLinesTest {
    private fun text(s: String, vararg spans: Span, center: Boolean = false) = Block.Text(s, spans.toList(), center)

    @Test fun twoPieces() {
        assertEquals(listOf(text("a"), text("b")), BlankLines.split(text("a\n\nb")))
    }

    @Test fun threeNewlines_onePieceBreak() {
        assertEquals(listOf(text("a"), text("b")), BlankLines.split(text("a\n\n\nb")))
    }

    @Test fun singleNewline_kept() {
        assertEquals(listOf(text("a\nb")), BlankLines.split(text("a\nb")))
    }

    @Test fun spanInsidePiece_rebased() {
        assertEquals(listOf(text("aa"), text("bold", Span.Bold(0, 4))), BlankLines.split(text("aa\n\nbold", Span.Bold(4, 8))))
    }

    @Test fun spanAcrossBreak_cutIntoBothPieces() {
        val url = "https://x.test"
        assertEquals(
            listOf(text("ab", Span.Link(1, 2, url)), text("cd", Span.Link(0, 2, url))),
            BlankLines.split(text("ab\n\ncd", Span.Link(1, 6, url))),
        )
    }

    @Test fun centered_keptOnEachPiece() {
        assertEquals(listOf(text("a", center = true), text("b", center = true)), BlankLines.split(text("a\n\nb", center = true)))
    }

    @Test fun quote_splitsIntoQuotes() {
        assertEquals(
            listOf(Block.Quote("a", emptyList()), Block.Quote("b", listOf(Span.Italic(0, 1)))),
            BlankLines.split(Block.Quote("a\n\nb", listOf(Span.Italic(3, 4)))),
        )
    }

    @Test fun headingAndRule_untouched() {
        val blocks = listOf(Block.Heading("h\n\nx", 2), Block.Rule)
        assertEquals(blocks, BlankLines.split(blocks))
    }

    @Test fun trailingAndLeadingBreaks_noEmptyPieces() {
        assertEquals(listOf(text("a")), BlankLines.split(text("\n\na\n\n")))
    }

    @Test fun listSplit_flattensInOrder() {
        assertEquals(listOf(text("a"), text("b"), Block.Rule, text("c")), BlankLines.split(listOf(text("a\n\nb"), Block.Rule, text("c"))))
    }

    @Test fun nbspBlankLine_splits() {
        for (gap in listOf("<br><br>", "<br>&nbsp;<br>", "<br> <br>")) {
            val converted = BlockConverter.convert(Jsoup.parseBodyFragment("<p>one${gap}two</p>", Ao3Urls.BASE_URI).body())
            assertEquals(gap, listOf(text("one"), text("two")), BlankLines.split(converted))
        }
    }
}
