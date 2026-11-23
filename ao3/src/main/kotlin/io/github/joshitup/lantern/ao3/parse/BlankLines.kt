package io.github.joshitup.lantern.ao3.parse

import io.github.joshitup.lantern.ao3.model.Block
import io.github.joshitup.lantern.ao3.model.Span

/**
 * Splits text at blank lines: a chapter written as one `<p>` with `<br><br>` between paragraphs becomes one
 * block per paragraph, like any other chapter. Text and quote blocks only; styles are cut to each piece.
 */
object BlankLines {
    /** Two or more line breaks, with only spaces between them. */
    private val BREAK = Regex("\n(?:[ \t\u00A0]*\n)+")

    private fun isEdge(c: Char) = c == '\n' || c == ' ' || c == '\t' || c == '\u00A0'

    fun split(blocks: List<Block>): List<Block> = blocks.flatMap { split(it) }

    /** The pieces of [block]; `listOf(block)` when it has no blank line (or isn't text). */
    fun split(block: Block): List<Block> = when (block) {
        is Block.Text -> pieces(block.text, block.spans).map { (t, s) -> Block.Text(t, s, block.center) }.ifEmpty { listOf(block) }
        is Block.Quote -> pieces(block.text, block.spans).map { (t, s) -> Block.Quote(t, s) }.ifEmpty { listOf(block) }
        else -> listOf(block)
    }

    /** Empty when [text] has no blank line, so the caller keeps its block as it is. */
    private fun pieces(text: String, spans: List<Span>): List<Pair<String, List<Span>>> {
        if (!BREAK.containsMatchIn(text)) return emptyList()
        val out = ArrayList<Pair<String, List<Span>>>()
        var from = 0
        fun piece(rawStart: Int, rawEnd: Int) {
            var s = rawStart
            var e = rawEnd
            while (s < e && isEdge(text[s])) s++
            while (e > s && isEdge(text[e - 1])) e--
            if (s < e) out += text.substring(s, e) to spans.mapNotNull { cut(it, s, e) }
        }
        for (m in BREAK.findAll(text)) {
            piece(from, m.range.first)
            from = m.range.last + 1
        }
        piece(from, text.length)
        return out
    }

    /** [span]'s part inside `[start, end)`, shifted to the piece; null when it doesn't overlap. */
    private fun cut(span: Span, start: Int, end: Int): Span? {
        val s = maxOf(span.start, start)
        val e = minOf(span.end, end)
        if (s >= e) return null
        return when (span) {
            is Span.Bold -> span.copy(start = s - start, end = e - start)
            is Span.Italic -> span.copy(start = s - start, end = e - start)
            is Span.Underline -> span.copy(start = s - start, end = e - start)
            is Span.Strike -> span.copy(start = s - start, end = e - start)
            is Span.Link -> span.copy(start = s - start, end = e - start)
        }
    }
}
