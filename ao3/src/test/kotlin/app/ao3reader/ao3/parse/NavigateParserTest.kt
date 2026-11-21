package app.ao3reader.ao3.parse

import app.ao3reader.ao3.model.ChapterRef
import org.junit.Assert.*
import org.junit.Test

class NavigateParserTest {
    @Test fun `parses 12 refs in order`() {
        val refs = NavigateParser.parse(javaClass.getResource("/fixtures/navigate.html")!!.readText())
        assertEquals(12, refs.size)
        assertEquals(ChapterRef(9001, 1, "Title 1"), refs[0])
        assertEquals(ChapterRef(9012, 12, "Title 12"), refs[11])
        assertEquals((1..12).toList(), refs.map { it.number })
    }

    @Test fun `real navigate page`() {
        val refs = NavigateParser.parse(javaClass.getResource("/fixtures/navigate_real.html")!!.readText())
        assertEquals(32, refs.size)
        assertEquals((1..32).toList(), refs.map { it.number })
        assertEquals(ChapterRef(2000120, 1, "Chapter Title 6"), refs[0])
        assertEquals(ChapterRef(2000124, 4, "Chapter Title 10"), refs[3])
        assertEquals(ChapterRef(2000147, 27, "Chapter Title 32, Part 1"), refs[26])   // comma in title
        assertEquals(ChapterRef(2000152, 32, "Chapter Title 37"), refs[31])
    }

    @Test fun `real navigate page of a single chapter work`() {
        val refs = NavigateParser.parse(javaClass.getResource("/fixtures/navigate_single_real.html")!!.readText())
        assertEquals(listOf(ChapterRef(2000159, 1, "Chapter 1")), refs)
    }
}
