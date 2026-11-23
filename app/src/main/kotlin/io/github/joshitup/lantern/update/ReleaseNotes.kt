package io.github.joshitup.lantern.update

import io.github.joshitup.lantern.ao3.model.Block
import io.github.joshitup.lantern.ao3.model.Span

/** Renders the small Markdown subset used in release notes into reader blocks; anything else stays plain text. */
object ReleaseNotes {
    private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
    private val BULLET = Regex("""^[-*]\s+(.*)$""")
    private val INLINE = Regex("""\*\*(.+?)\*\*|\[([^\]]+)]\((https?://[^)\s]+)\)""")

    fun toBlocks(markdown: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val para = mutableListOf<String>()
        fun flush() {
            if (para.isNotEmpty()) blocks += inline(para.joinToString(" ")).also { para.clear() }
        }
        for (raw in markdown.lines()) {
            val line = raw.trim()
            val heading = HEADING.matchEntire(line)
            val bullet = BULLET.matchEntire(line)
            when {
                line.isEmpty() -> flush()
                heading != null -> {
                    flush()
                    blocks += Block.Heading(heading.groupValues[2].trim(), heading.groupValues[1].length)
                }
                bullet != null -> {
                    flush()
                    blocks += inline("• " + bullet.groupValues[1])
                }
                else -> para += line
            }
        }
        flush()
        return blocks
    }

    private fun inline(src: String): Block.Text {
        val out = StringBuilder()
        val spans = mutableListOf<Span>()
        var last = 0
        for (m in INLINE.findAll(src)) {
            out.append(src, last, m.range.first)
            val start = out.length
            if (m.groupValues[1].isNotEmpty()) {
                out.append(m.groupValues[1])
                spans += Span.Bold(start, out.length)
            } else {
                out.append(m.groupValues[2])
                spans += Span.Link(start, out.length, m.groupValues[3])
            }
            last = m.range.last + 1
        }
        out.append(src, last, src.length)
        return Block.Text(out.toString(), spans)
    }
}
