package io.github.joshitup.lantern.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class BookmarkTagsTest {
    @Test fun `a comma finishes a tag, the rest is still being typed`() {
        assertEquals(emptyList<String>() to "fluf", splitTags("fluf"))
        assertEquals(listOf("cozy") to "", splitTags("cozy,"))
        assertEquals(listOf("a", "b c") to " d", splitTags(" a , b c, d"))
        assertEquals(emptyList<String>() to "", splitTags(",,"))
    }

    @Test fun `commit finishes the remainder too`() {
        assertEquals(listOf("a", "b") to "", splitTags("a, b ", commit = true))
        assertEquals(emptyList<String>() to "", splitTags("   ", commit = true))
    }

    @Test fun `adding skips tags already there, ignoring case`() {
        assertEquals(listOf("Cozy", "drama"), addTags(listOf("Cozy"), listOf("cozy", "drama", "Drama")))
    }
}
