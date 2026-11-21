package app.ao3reader.ui.search

import app.ao3reader.ao3.model.Rating
import app.ao3reader.ao3.model.SearchQuery
import app.ao3reader.ao3.model.SortColumn
import app.ao3reader.ao3.net.FetchResult
import app.ao3reader.data.db.ProgressEntity
import app.ao3reader.ui.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchUiHelpersTest {
    @Test fun `rating is single valued`() {
        assertEquals(setOf(Rating.TEEN), toggleRating(emptySet(), Rating.TEEN))
        assertEquals(setOf(Rating.MATURE), toggleRating(setOf(Rating.TEEN), Rating.MATURE))
        assertEquals(emptySet<Rating>(), toggleRating(setOf(Rating.TEEN), Rating.TEEN))
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
