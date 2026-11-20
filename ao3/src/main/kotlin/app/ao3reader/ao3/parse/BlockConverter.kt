package app.ao3reader.ao3.parse

import app.ao3reader.ao3.model.Block
import app.ao3reader.ao3.model.Span
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** Converts AO3 user HTML into native-renderable blocks. Never throws on odd markup. */
object BlockConverter {
    private val BLOCK_TAGS = setOf(
        "p", "div", "blockquote", "h1", "h2", "h3", "h4", "h5", "h6", "hr", "ul", "ol", "li",
        "table", "pre", "center", "section", "article", "header", "footer", "dl", "dt", "dd",
    )
    private val BLOCK_SELECTOR = BLOCK_TAGS.joinToString(",")
    private val SKIP_TAGS = setOf("script", "style", "head", "noscript")

    fun convert(root: Element): List<Block> {
        val out = ArrayList<Block>()
        walk(root.childNodes(), out, false)
        return out
    }

    private fun isWs(c: Char) = c == ' ' || c == '\n' || c == '\t' || c == '\r' || c == '\u000C' || c == '\u00A0'

    private fun containsBlock(el: Element) = el.select(BLOCK_SELECTOR).isNotEmpty()

    private fun walk(nodes: List<Node>, out: MutableList<Block>, center: Boolean) {
        var run = Inline()
        fun flush() {
            run.toBlock(center)?.let { out.add(it) }
            run = Inline()
        }
        for (n in nodes) {
            when {
                n is TextNode -> run.text(n.wholeText)
                n !is Element -> {}
                n.normalName() in SKIP_TAGS -> {}
                n.normalName() in BLOCK_TAGS -> { flush(); block(n, out, center) }
                n.normalName() != "br" && containsBlock(n) -> { flush(); walk(n.childNodes(), out, center) }
                else -> run.element(n)
            }
        }
        flush()
    }

    private fun isCentered(e: Element): Boolean =
        e.attr("align").equals("center", true) ||
            e.attr("style").replace(" ", "").contains("text-align:center", true)

    private fun plain(e: Element): String =
        (Inline().also { it.children(e) }.toBlock(false) as? Block.Text)?.text.orEmpty()

    private fun block(e: Element, out: MutableList<Block>, center: Boolean) {
        when (val tag = e.normalName()) {
            "hr" -> out.add(Block.Rule)
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                val t = plain(e)
                if (t.isNotEmpty()) out.add(Block.Heading(t, tag[1] - '0'))
            }
            "blockquote" -> {
                val inner = ArrayList<Block>()
                walk(e.childNodes(), inner, center)
                inner.forEach {
                    when (it) {
                        is Block.Text -> out.add(Block.Quote(it.text, it.spans))
                        else -> out.add(it)
                    }
                }
            }
            "ul", "ol" -> {
                var i = 0
                for (li in e.children()) {
                    if (li.normalName() != "li") { walk(listOf(li), out, center); continue }
                    i++
                    val marker = if (tag == "ol") "$i. " else "• "
                    val inner = ArrayList<Block>()
                    walk(li.childNodes(), inner, center)
                    inner.forEachIndexed { idx, b ->
                        if (idx == 0 && b is Block.Text) {
                            out.add(Block.Text(marker + b.text, b.spans.map { shift(it, marker.length) }, b.center))
                        } else out.add(b)
                    }
                }
            }
            "table" -> {
                for (tr in e.select("tr")) {
                    val cells = tr.children().filter { it.normalName() == "td" || it.normalName() == "th" }
                        .map { plain(it) }
                    if (cells.any { it.isNotEmpty() }) out.add(Block.Text(cells.joinToString(" | "), emptyList()))
                }
            }
            "p", "dt", "dd", "pre" -> {
                val c = center || isCentered(e)
                if (containsBlock(e)) walk(e.childNodes(), out, c)
                else Inline().also { it.children(e) }.toBlock(c)?.let { out.add(it) }
            }
            else -> walk(e.childNodes(), out, center || tag == "center" || isCentered(e))
        }
    }

    private fun linkMaker(href: String): (Int, Int) -> Span = { s, en -> Span.Link(s, en, href) }

    /**
     * The absolute http(s) target of link [a], resolved against the document's base URI (callers parse
     * with [Ao3Urls.BASE_URI]); null for fragment-only links (footnote jumps that can't scroll here),
     * other schemes and unresolvable hrefs. The link text is kept either way.
     */
    private fun linkTarget(a: Element): String? {
        val raw = a.attr("href").trim()
        if (raw.isEmpty() || raw.startsWith("#")) return null
        val abs = a.absUrl("href")
        return abs.takeIf { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) }
    }

    private fun shift(s: Span, by: Int): Span = when (s) {
        is Span.Bold -> s.copy(start = s.start + by, end = s.end + by)
        is Span.Italic -> s.copy(start = s.start + by, end = s.end + by)
        is Span.Underline -> s.copy(start = s.start + by, end = s.end + by)
        is Span.Strike -> s.copy(start = s.start + by, end = s.end + by)
        is Span.Link -> s.copy(start = s.start + by, end = s.end + by)
    }

    /** Accumulates collapsed inline text and style spans whose offsets index the final text. */
    private class Inline {
        private class Frame(val make: (Int, Int) -> Span) { var start = -1 }

        private val sb = StringBuilder()
        private val spans = ArrayList<Span>()
        private val open = ArrayList<Frame>()
        private var pendingSpace = false

        private fun emit(c: Char) {
            if (c == '\n') {
                if (sb.isEmpty()) return
                pendingSpace = false
            } else if (pendingSpace) {
                pendingSpace = false
                if (sb.isNotEmpty() && sb.last() != '\n') sb.append(' ')
            }
            sb.append(c)
            for (f in open) if (f.start < 0) f.start = sb.length - 1
        }

        fun text(s: String) {
            for (c in s) if (isWs(c)) pendingSpace = true else emit(c)
        }

        fun children(e: Element) { for (n in e.childNodes()) node(n) }

        private fun node(n: Node) {
            if (n is TextNode) text(n.wholeText) else if (n is Element) element(n)
        }

        private fun maker(tag: String, e: Element): ((Int, Int) -> Span)? = when (tag) {
            "b", "strong" -> ({ s: Int, en: Int -> Span.Bold(s, en) })
            "i", "em" -> ({ s: Int, en: Int -> Span.Italic(s, en) })
            "u", "ins" -> ({ s: Int, en: Int -> Span.Underline(s, en) })
            "s", "strike", "del" -> ({ s: Int, en: Int -> Span.Strike(s, en) })
            "a" -> linkTarget(e)?.let { linkMaker(it) }
            else -> null
        }

        fun element(e: Element) {
            val tag = e.normalName()
            if (tag in SKIP_TAGS) return
            if (tag == "br") { emit('\n'); return }
            if (tag == "img") { "[image]".forEach { emit(it) }; return }
            val make: ((Int, Int) -> Span)? = maker(tag, e)
            if (make == null) { children(e); return }
            val f = Frame(make)
            open.add(f)
            children(e)
            open.remove(f)
            if (f.start >= 0) spans.add(make(f.start, sb.length))
        }

        fun toBlock(center: Boolean): Block? {
            var end = sb.length
            while (end > 0 && (sb[end - 1] == '\n' || sb[end - 1] == ' ')) end--
            if (end == 0) return null
            val clamped = spans.mapNotNull { s ->
                val e = minOf(s.end, end)
                if (s.start >= e) null else if (e == s.end) s else when (s) {
                    is Span.Bold -> s.copy(end = e)
                    is Span.Italic -> s.copy(end = e)
                    is Span.Underline -> s.copy(end = e)
                    is Span.Strike -> s.copy(end = e)
                    is Span.Link -> s.copy(end = e)
                }
            }.sortedBy { it.start }
            return Block.Text(sb.substring(0, end), clamped, center)
        }
    }
}
