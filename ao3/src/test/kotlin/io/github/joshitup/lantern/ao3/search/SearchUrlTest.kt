package io.github.joshitup.lantern.ao3.search

import io.github.joshitup.lantern.ao3.model.*
import org.junit.Assert.*
import org.junit.Test

class SearchUrlTest {
    @Test fun `default query builds sorted search url`() {
        val url = SearchQuery().toUrl(1)
        assertEquals("/works/search", url.encodedPath)
        assertEquals("1", url.queryParameter("page"))
        assertEquals("revised_at", url.queryParameter("work_search[sort_column]"))
        assertEquals("desc", url.queryParameter("work_search[sort_direction]"))
    }

    @Test fun `tags joined with commas`() {
        val url = SearchQuery(
            fandoms = listOf("A", "B"), characters = listOf("A", "B"), relationships = listOf("A", "B"),
            freeforms = listOf("A", "B"), excludedTags = listOf("A", "B"),
        ).toUrl(1)
        for (k in listOf("fandom_names", "character_names", "relationship_names", "freeform_names", "excluded_tag_names"))
            assertEquals(k, "A,B", url.queryParameter("work_search[$k]"))
    }

    @Test fun `id sets are repeated array params`() {
        val url = SearchQuery(
            ratings = setOf(Rating.EXPLICIT), warnings = setOf(Warning.MCD, Warning.VIOLENCE),
            categories = setOf(Category.FF, Category.GEN),
        ).toUrl(1)
        assertEquals("13", url.queryParameter("work_search[rating_ids]"))
        assertEquals(setOf("17", "18"), url.queryParameterValues("work_search[archive_warning_ids][]").toSet())
        assertEquals(2, url.queryParameterValues("work_search[archive_warning_ids][]").size)
        assertEquals(setOf("21", "116"), url.queryParameterValues("work_search[category_ids][]").toSet())
    }

    @Test fun `completion and crossover map to T F blank`() {
        assertEquals("T", SearchQuery(completion = Completion.COMPLETE).toUrl(1).queryParameter("work_search[complete]"))
        assertEquals("F", SearchQuery(completion = Completion.IN_PROGRESS).toUrl(1).queryParameter("work_search[complete]"))
        assertNull(SearchQuery().toUrl(1).queryParameter("work_search[complete]"))
        assertEquals("T", SearchQuery(crossover = Crossover.INCLUDE_ONLY).toUrl(1).queryParameter("work_search[crossover]"))
        assertEquals("F", SearchQuery(crossover = Crossover.EXCLUDE).toUrl(1).queryParameter("work_search[crossover]"))
        assertNull(SearchQuery().toUrl(1).queryParameter("work_search[crossover]"))
    }

    @Test fun `word range`() {
        val url = SearchQuery(wordsFrom = 1000).toUrl(1)
        assertEquals("1000", url.queryParameter("work_search[words_from]"))
        assertNull(url.queryParameter("work_search[words_to]"))
    }

    @Test fun `blank fields omitted`() {
        val url = SearchQuery().toUrl(2)
        for (i in 0 until url.querySize) assertFalse(url.queryParameterName(i), url.queryParameterValue(i).isNullOrEmpty())
        assertNull(url.queryParameter("work_search[query]"))
        assertNull(url.queryParameter("work_search[title]"))
    }

    @Test fun exclusions_allSentAsExcludedTagNames() {
        val url = SearchQuery(
            excludedTags = listOf("Fluff"), excludedRelationships = listOf("A/B"), excludedCharacters = listOf("C"),
            excludedRatings = setOf(Rating.EXPLICIT), excludedWarnings = setOf(Warning.MCD), excludedCategories = setOf(Category.MM),
        ).toUrl(1)
        assertEquals("Fluff,A/B,C,Explicit,Major Character Death,M/M", url.queryParameter("work_search[excluded_tag_names]"))
    }

    @Test fun excludedNames_deduplicated() {
        val url = SearchQuery(excludedTags = listOf("c", " "), excludedCharacters = listOf("C")).toUrl(1)
        assertEquals("c", url.queryParameter("work_search[excluded_tag_names]"))
    }

    @Test fun noExclusions_noParameter() {
        assertNull(SearchQuery().toUrl(1).queryParameter("work_search[excluded_tag_names]"))
    }

    @Test fun oldPresetJson_decodes() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        assertEquals(
            SearchQuery(fandoms = listOf("F"), excludedTags = listOf("X"), ratings = setOf(Rating.TEEN)),
            json.decodeFromString(SearchQuery.serializer(), """{"fandoms":["F"],"excludedTags":["X"],"ratings":["TEEN"]}"""),
        )
    }
}
