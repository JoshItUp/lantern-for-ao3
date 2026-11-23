package io.github.joshitup.lantern.ao3.parse

import io.github.joshitup.lantern.ao3.model.Block
import io.github.joshitup.lantern.ao3.model.Span
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class BlockConverterTest {
    /** Parsed as callers do, with AO3 as the base URI. */
    private fun conv(html: String) = BlockConverter.convert(Jsoup.parseBodyFragment(html, Ao3Urls.BASE_URI).body())

    @Test fun `paragraph with inline styles`() {
        val b = conv("<p>a <strong>b</strong> <em>c</em> <a href=\"https://example.com/u\">d</a></p>")
        assertEquals(1, b.size)
        val t = b[0] as Block.Text
        assertEquals("a b c d", t.text)
        assertEquals(listOf(Span.Bold(2, 3), Span.Italic(4, 5), Span.Link(6, 7, "https://example.com/u")), t.spans)
    }

    @Test fun `relative links resolve against AO3`() {
        val t = conv("""<p><a href="/works/1/chapters/2">next</a> and <a href="works/3">rel</a></p>""")[0] as Block.Text
        assertEquals(
            listOf(Span.Link(0, 4, "https://archiveofourown.org/works/1/chapters/2"), Span.Link(9, 12, "https://archiveofourown.org/works/3")),
            t.spans,
        )
    }

    @Test fun `fragment-only links keep their text but lose the link`() {
        val t = conv("""<p>see note<a href="#fn1"><sup>1</sup></a> here</p>""")[0] as Block.Text
        assertEquals("see note1 here", t.text)
        assertTrue(t.spans.none { it is Span.Link })
    }

    @Test fun `only http and https links are kept`() {
        val t = conv("""<p><a href="mailto:a@b.c">mail</a> <a href="javascript:alert(1)">js</a> <a href="http://x.org/">http</a> <a href="">empty</a></p>""")[0] as Block.Text
        assertEquals("mail js http empty", t.text)
        assertEquals(listOf(Span.Link(8, 12, "http://x.org/")), t.spans)
    }

    @Test fun `links inside a cloned chapter body still resolve`() {
        val html = """<div id="chapters"><div class="userstuff module"><p><a href="/tags/x/works">tag</a></p></div></div>"""
        val c = ChapterParser.parse("<html><body><div id=\"workskin\">$html</div></body></html>", 1)
        assertEquals(listOf(Span.Link(0, 3, "https://archiveofourown.org/tags/x/works")), (c.body[0] as Block.Text).spans)
    }

    @Test fun `br becomes newline`() {
        val t = conv("<p>one<br>two</p>")[0] as Block.Text
        assertEquals("one\ntwo", t.text)
    }

    @Test fun `hr becomes Rule`() {
        assertEquals(listOf<Block>(Block.Rule), conv("<hr>"))
    }

    @Test fun `headings carry level`() {
        for (l in 1..6) assertEquals(Block.Heading("T", l), conv("<h$l>T</h$l>")[0])
    }

    @Test fun `blockquote becomes Quote`() {
        val q = conv("<blockquote><p>quoted <em>x</em></p></blockquote>")[0] as Block.Quote
        assertEquals("quoted x", q.text)
        assertEquals(listOf<Span>(Span.Italic(7, 8)), q.spans)
    }

    @Test fun `center aligned p sets center`() {
        assertTrue((conv("<p align=\"center\">x</p>")[0] as Block.Text).center)
        assertTrue((conv("<p style=\"text-align:center\">x</p>")[0] as Block.Text).center)
        assertFalse((conv("<p>x</p>")[0] as Block.Text).center)
    }

    @Test fun `div-wrapped paragraphs flatten in order`() {
        val b = conv("<div><p>1</p><div><p>2</p></div><p>3</p></div>")
        assertEquals(listOf("1", "2", "3"), b.map { (it as Block.Text).text })
    }

    @Test fun `img and table degrade to text`() {
        assertEquals(Block.Text("[image]", emptyList()), conv("<p><img src=\"x.png\"></p>")[0])
        val t = conv("<table><tr><td>a</td><td>b</td></tr><tr><td>c</td><td>d</td></tr></table>")
        assertEquals(listOf("a | b", "c | d"), t.map { (it as Block.Text).text })
    }

    @Test fun `empty paragraphs dropped`() {
        assertTrue(conv("<p></p><p>&nbsp;</p><p> <br> </p>").isEmpty())
    }

    @Test fun `whitespace collapsed and spans stay aligned`() {
        val t = conv("<p>  a \n  <b> b </b>  c </p>")[0] as Block.Text
        assertEquals("a b c", t.text)
        assertEquals(listOf<Span>(Span.Bold(2, 3)), t.spans)
    }

    @Test fun `loose text and nested spans`() {
        val b = conv("loose <p><b><i>x</i></b></p>")
        assertEquals("loose", (b[0] as Block.Text).text)
        val t = b[1] as Block.Text
        assertTrue(t.spans.containsAll(listOf(Span.Bold(0, 1), Span.Italic(0, 1))))
    }

    @Test fun `real chapter body converts paragraph by paragraph`() {
        val html = javaClass.getResource("/fixtures/chapter_later_real.html")!!.readText()
        val body = Jsoup.parse(html).selectFirst("#chapters div.userstuff.module")!!
        val blocks = BlockConverter.convert(body)
        // The landmark heading survives conversion; ChapterParser is what strips it.
        assertEquals(Block.Heading("Chapter Text", 3), blocks[0])
        assertEquals(6, blocks.size)
        val third = blocks[3] as Block.Text
        assertTrue(third.text.startsWith("Lacus qui non ut velit, sollicitudin amet. Ex qui nunc porttitor"))
        val italic = third.spans.single() as Span.Italic
        assertEquals("qui", third.text.substring(italic.start, italic.end))
    }
}
