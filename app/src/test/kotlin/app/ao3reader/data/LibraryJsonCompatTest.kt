package app.ao3reader.data

import app.ao3reader.ao3.model.WorkBlurb
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryJsonCompatTest {
    @Test fun `old cached blurb without authorLinks or series decodes`() {
        val json = """{"workId":7,"title":"T","authors":["A"],"fandoms":[],"rating":null,"warnings":[],
            "categories":[],"complete":null,"relationships":[],"characters":[],"freeforms":[],
            "summaryHtml":null,"language":null,"words":null,"chaptersPosted":null,"chaptersTotal":null,
            "kudos":null,"hits":null,"bookmarks":null,"comments":null,"updated":null}"""
        val w = libraryJson.decodeFromString<WorkBlurb>(json)
        assertEquals(7L, w.workId)
        assertTrue(w.authorLinks.isEmpty())
        assertTrue(w.series.isEmpty())
    }
}
