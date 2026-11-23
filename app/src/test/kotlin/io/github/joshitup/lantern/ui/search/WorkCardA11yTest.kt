package io.github.joshitup.lantern.ui.search

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.joshitup.lantern.ao3.model.AuthorLink
import io.github.joshitup.lantern.ao3.model.SeriesLink
import io.github.joshitup.lantern.ao3.model.WorkBlurb
import io.github.joshitup.lantern.ui.common.cardLinks
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkCardA11yTest {
    @get:Rule val compose = createComposeRule()

    private val work = WorkBlurb(
        workId = 1, title = "Synthetic Title", authors = listOf("Author One", "Author Two"), fandoms = listOf("Fandom"),
        rating = null, warnings = emptyList(), categories = emptyList(), complete = null, relationships = emptyList(),
        characters = emptyList(), freeforms = emptyList(), summaryHtml = null, language = null, words = null,
        chaptersPosted = null, chaptersTotal = null, kudos = null, hits = null, bookmarks = null, comments = null, updated = null,
        authorLinks = listOf(AuthorLink("Author One", "user1", "Author One"), AuthorLink("Author Two", "user2", "Author Two")),
        series = listOf(SeriesLink(7, "Synthetic Series", part = 2)),
    )

    /** Everything TalkBack reads for the card itself (its merged text and descriptions). */
    private fun cardSpeech(): String {
        val node = compose.onNode(hasClickAction() and hasText("Synthetic Title", substring = true)).fetchSemanticsNode()
        val c = node.config
        return (c.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            c.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }).joinToString(" ")
    }

    @Test fun tappableByline_isReadWithTheCard() {
        val links = cardLinks(work.authors, work.authorLinks, work.series)
        compose.setContent {
            WorkCard(work, emptyList(), badge = null, onClick = {}, links = links, onOpenWorkList = { _, _ -> })
        }
        val speech = cardSpeech()
        assertTrue(speech, speech.contains("by Author One, Author Two"))
        assertTrue(speech, speech.contains("Part 2 of Synthetic Series"))
        assertTrue(speech, Regex("\\bby\\b").findAll(speech).count() == 1)
    }

    @Test fun anonymousAuthor_readOnce() {
        val anon = work.copy(authors = listOf("Anonymous"), authorLinks = emptyList())
        val links = cardLinks(anon.authors, anon.authorLinks, anon.series)
        compose.setContent {
            WorkCard(anon, emptyList(), badge = null, onClick = {}, links = links, onOpenWorkList = { _, _ -> })
        }
        val speech = cardSpeech()
        assertTrue(speech, Regex("Anonymous").findAll(speech).count() == 1)
    }

    @Test fun plainByline_isReadOnce() {
        compose.setContent { WorkCard(work, emptyList(), badge = null, onClick = {}) }
        val speech = cardSpeech()
        assertTrue(speech, Regex("by Author One").findAll(speech).count() == 1)
    }
}
