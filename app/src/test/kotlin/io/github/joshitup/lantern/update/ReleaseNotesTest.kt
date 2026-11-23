package io.github.joshitup.lantern.update

import io.github.joshitup.lantern.ao3.model.Block
import io.github.joshitup.lantern.ao3.model.Span
import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotesTest {
    @Test fun heading() {
        assertEquals(listOf<Block>(Block.Heading("Added", 2)), ReleaseNotes.toBlocks("## Added"))
    }

    @Test fun bulletsBecomeTextWithPrefix() {
        val blocks = ReleaseNotes.toBlocks("- one\n* two")
        assertEquals(listOf<Block>(Block.Text("• one", emptyList()), Block.Text("• two", emptyList())), blocks)
    }

    @Test fun boldSpan() {
        val blocks = ReleaseNotes.toBlocks("a **bold** b")
        assertEquals(listOf<Block>(Block.Text("a bold b", listOf(Span.Bold(2, 6)))), blocks)
    }

    @Test fun linkSpan() {
        val blocks = ReleaseNotes.toBlocks("see [docs](https://example.com/x) now")
        assertEquals(listOf<Block>(Block.Text("see docs now", listOf(Span.Link(4, 8, "https://example.com/x")))), blocks)
    }

    @Test fun boldInsideBullet() {
        val blocks = ReleaseNotes.toBlocks("- **hi** there")
        assertEquals(listOf<Block>(Block.Text("• hi there", listOf(Span.Bold(2, 4)))), blocks)
    }

    @Test fun blankLinesSeparateParagraphs() {
        val blocks = ReleaseNotes.toBlocks("first\nline\n\nsecond\r\n\r\n\r\nthird")
        assertEquals(
            listOf<Block>(Block.Text("first line", emptyList()), Block.Text("second", emptyList()), Block.Text("third", emptyList())),
            blocks,
        )
    }

    @Test fun unknownMarkdownPassesThrough() {
        val blocks = ReleaseNotes.toBlocks("`code` and > quote and ![img](x)")
        assertEquals(listOf<Block>(Block.Text("`code` and > quote and ![img](x)", emptyList())), blocks)
    }

    @Test fun emptyInput() {
        assertEquals(emptyList<Block>(), ReleaseNotes.toBlocks("  \n "))
    }
}
