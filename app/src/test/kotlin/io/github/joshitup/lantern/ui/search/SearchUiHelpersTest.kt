package io.github.joshitup.lantern.ui.search

import io.github.joshitup.lantern.ao3.model.Category
import io.github.joshitup.lantern.ao3.model.Rating
import io.github.joshitup.lantern.ao3.model.Warning
import io.github.joshitup.lantern.ao3.model.SearchQuery
import io.github.joshitup.lantern.ao3.model.SortColumn
import io.github.joshitup.lantern.ao3.net.FetchResult
import io.github.joshitup.lantern.data.db.ProgressEntity
import io.github.joshitup.lantern.ui.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchUiHelpersTest {
    @Test fun cycle_offIncludedExcludedOff() {
        val mm = Category.MM
        var state = emptySet<Category>() to emptySet<Category>()
        state = cycle(mm, state.first, state.second); assertEquals(setOf(mm) to emptySet<Category>(), state)
        assertEquals(TriState.INCLUDED, triState(mm, state.first, state.second))
        state = cycle(mm, state.first, state.second); assertEquals(emptySet<Category>() to setOf(mm), state)
        assertEquals(TriState.EXCLUDED, triState(mm, state.first, state.second))
        state = cycle(mm, state.first, state.second); assertEquals(emptySet<Category>() to emptySet<Category>(), state)
        assertEquals(TriState.OFF, triState(mm, state.first, state.second))
    }

    @Test fun ratingCycle_singleInclude() {
        assertEquals(
            setOf(Rating.MATURE) to setOf(Rating.EXPLICIT),
            cycle(Rating.MATURE, setOf(Rating.TEEN), setOf(Rating.EXPLICIT), singleInclude = true),
        )
    }

    @Test fun ratingCycle_neverBoth() {
        assertEquals(
            emptySet<Rating>() to setOf(Rating.MATURE),
            cycle(Rating.MATURE, setOf(Rating.MATURE), emptySet(), singleInclude = true),
        )
    }

    @Test fun activeFilterCount_countsExclusions() {
        assertEquals(1, activeFilterCount(SearchQuery(excludedCharacters = listOf("C"))))
        assertEquals(1, activeFilterCount(SearchQuery(excludedRelationships = listOf("A/B"))))
        assertEquals(1, activeFilterCount(SearchQuery(excludedRatings = setOf(Rating.EXPLICIT))))
        assertEquals(1, activeFilterCount(SearchQuery(ratings = setOf(Rating.TEEN), excludedRatings = setOf(Rating.EXPLICIT))))
        assertEquals(1, activeFilterCount(SearchQuery(excludedWarnings = setOf(Warning.MCD))))
        assertEquals(1, activeFilterCount(SearchQuery(excludedCategories = setOf(Category.MM))))
    }

    @Test fun reset_clearsExclusions() {
        val full = SearchQuery(
            anyField = "x", excludedTags = listOf("T"), excludedCharacters = listOf("C"), excludedRelationships = listOf("A/B"),
            excludedRatings = setOf(Rating.EXPLICIT), excludedWarnings = setOf(Warning.MCD), excludedCategories = setOf(Category.MM),
        )
        assertEquals(SearchQuery(anyField = "x"), resetFilters(full))
    }

    @Test fun `badge prefers progress over read mark`() {
        assertEquals("✓ Ch 4", badgeText(4, read = true, downloaded = false))
        assertEquals("Read", badgeText(null, read = true, downloaded = false))
        assertNull(badgeText(null, read = false, downloaded = false))
    }

    @Test fun badge_downloadedOnly() {
        assertEquals("⤓", badgeText(null, read = false, downloaded = true))
    }

    @Test fun badge_progressAndDownloaded() {
        assertEquals("✓ Ch 4 · ⤓", badgeText(4, read = true, downloaded = true))
        assertEquals("Read · ⤓", badgeText(null, read = true, downloaded = true))
    }

    @Test fun badgeLabel_readsDownloaded() {
        assertEquals("Downloaded", badgeLabel(badgeText(null, read = false, downloaded = true)!!))
        assertEquals("Chapter 4 · Downloaded", badgeLabel(badgeText(4, read = true, downloaded = true)!!))
        assertEquals("Read · Downloaded", badgeLabel(badgeText(null, read = true, downloaded = true)!!))
        assertEquals("Chapter 4", badgeLabel(badgeText(4, read = false, downloaded = false)!!))
        assertEquals("Read", badgeLabel(badgeText(null, read = true, downloaded = false)!!))
    }

    @Test fun `chapters text`() {
        assertEquals("4/12", chaptersText(4, 12))
        assertEquals("4/?", chaptersText(4, null))
        assertNull(chaptersText(null, 3))
    }

    @Test fun `error messages`() {
        assertEquals("AO3 is blocking requests right now.", errorMessage(FetchResult.Blocked))
        assertEquals("AO3 asked us to slow down. Try again in 30 s.", errorMessage(FetchResult.RateLimited(30)))
        assertEquals("Couldn't load results.", errorMessage(FetchResult.NotFound))
        assertEquals("Couldn't load results.", errorMessage(FetchResult.Failed(null)))
    }

    @Test fun `continue label`() {
        val p = ProgressEntity(1, "My Work", null, 4, null, 0, 0, 0)
        assertEquals("My Work — Ch 4/?", continueLabel(p))
        assertEquals("My Work — Ch 4/12", continueLabel(p.copy(chaptersTotal = 12)))
    }

    @Test fun `filter count ignores any field`() {
        assertEquals(0, activeFilterCount(SearchQuery(anyField = "x")))
        assertEquals(3, activeFilterCount(SearchQuery(fandoms = listOf("F"), ratings = setOf(Rating.TEEN), sort = SortColumn.KUDOS)))
    }

    @Test fun `reader route`() {
        assertEquals("reader/7?number=1", Routes.reader(7, null, 1))
        assertEquals("reader/7?number=3&chapterId=99", Routes.reader(7, 99, 3))
    }
}
