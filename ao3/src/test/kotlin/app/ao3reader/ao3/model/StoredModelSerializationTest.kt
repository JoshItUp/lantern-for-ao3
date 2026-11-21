package app.ao3reader.ao3.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoredModelSerializationTest {
    private val spans = listOf(
        Span.Bold(0, 1), Span.Italic(1, 2), Span.Underline(2, 3), Span.Strike(3, 4), Span.Link(4, 5, "https://x.test/"),
    )
    private val blocks = listOf(
        Block.Text("hello", spans, center = true), Block.Heading("H", 3), Block.Quote("q", spans), Block.Rule,
    )
    private val content = ChapterContent(
        workId = 7, ref = ChapterRef(9, 2, "Two"), workTitle = "W", authors = listOf("A"),
        beginNotes = blocks, body = blocks, endNotes = blocks, summary = blocks,
        authorLinks = listOf(AuthorLink("A", "a", "a")), series = listOf(SeriesLink(1, "S", 2)),
    )

    @Test fun roundTrip() {
        val json = Json.encodeToString(content)
        assertEquals(content, Json.decodeFromString<ChapterContent>(json))
    }

    @Test fun discriminatorsAreStable() {
        val json = Json.encodeToString(content)
        for (t in listOf("bold", "italic", "underline", "strike", "link", "text", "heading", "quote", "rule")) {
            assertTrue(t, json.contains("\"type\":\"$t\""))
        }
    }
}
