package io.github.joshitup.lantern.ao3.parse

import org.junit.Assert.*
import org.junit.Test

class AutocompleteTest {
    @Test fun `parses names`() {
        assertEquals(listOf("Fandom A"),
            Autocomplete.parseOrNull("[{\"id\":\"Fandom A\",\"name\":\"Fandom A\"}]"))
        val f = javaClass.getResource("/fixtures/autocomplete_fandom.json")!!.readText()
        assertEquals(2, Autocomplete.parseOrNull(f)!!.size)
    }

    @Test fun `plain string elements are used as names`() {
        assertEquals(listOf("Alpha", "Beta"), Autocomplete.parseOrNull("[\"Alpha\", {\"id\":\"Beta\",\"name\":\"Beta\"}, \"\", 3]"))
    }

    @Test fun `object without name falls back to id`() {
        assertEquals(listOf("Gamma"), Autocomplete.parseOrNull("[{\"id\":\"Gamma\"}]"))
    }

    @Test fun `parseOrNull distinguishes an empty array from invalid input`() {
        assertEquals(emptyList<String>(), Autocomplete.parseOrNull("[]"))
        assertEquals(listOf("Gamma"), Autocomplete.parseOrNull("[{\"id\":\"Gamma\"}]"))
        assertEquals(null, Autocomplete.parseOrNull("<html><title>Just a moment...</title></html>"))
        assertEquals(null, Autocomplete.parseOrNull("{}"))
        assertEquals(null, Autocomplete.parseOrNull(""))
    }

    @Test fun `real fandom autocomplete`() {
        val names = Autocomplete.parseOrNull(javaClass.getResource("/fixtures/autocomplete_fandom_real.json")!!.readText())!!
        assertEquals(16, names.size)
        assertEquals("Fandom C", names[0])
        assertEquals("Fandom A", names[2])
        assertTrue("Fandom E" in names)
    }

    @Test fun `real relationship autocomplete`() {
        val names = Autocomplete.parseOrNull(javaClass.getResource("/fixtures/autocomplete_relationship_real.json")!!.readText())!!
        assertEquals(16, names.size)
        assertEquals("Character B/Character A", names[0])
        assertEquals("Character B & Character A & Character C", names[1])
    }
}
