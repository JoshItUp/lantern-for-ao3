package io.github.joshitup.lantern.ui.common

import io.github.joshitup.lantern.ao3.model.AuthorLink
import io.github.joshitup.lantern.ao3.model.SeriesLink
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkLinksTest {
    private val pseud = AuthorLink("pseud1 (author65)", "author65", "pseud1")
    private val plain = AuthorLink("author65", "author65", "author65")

    @Test fun `authors map to their works lists in byline order`() {
        val l = cardLinks(listOf("pseud1 (author65)", "Anonymous", "author65"), listOf(pseud, plain), emptyList())
        assertEquals(
            listOf(
                WorkLink("pseud1 (author65)", "https://archiveofourown.org/users/author65/pseuds/pseud1/works".toHttpUrl(), "Works by pseud1 (author65)"),
                WorkLink("Anonymous", null, "Anonymous"),
                WorkLink("author65", "https://archiveofourown.org/users/author65/works".toHttpUrl(), "Works by author65"),
            ),
            l.authors,
        )
        assertTrue(l.series.isEmpty())
    }

    @Test fun `series carry their part`() {
        val l = cardLinks(emptyList(), emptyList(), listOf(SeriesLink(5, "Saga", 2), SeriesLink(6, "Other", null)))
        assertEquals(
            listOf(
                WorkLink("Part 2 of Saga", "https://archiveofourown.org/series/5".toHttpUrl(), "Saga"),
                WorkLink("Other", "https://archiveofourown.org/series/6".toHttpUrl(), "Other"),
            ),
            l.series,
        )
    }

    @Test fun `links to the list being shown are not tappable`() {
        val here = "https://archiveofourown.org/series/5".toHttpUrl()
        val l = cardLinks(listOf("author65"), listOf(plain), listOf(SeriesLink(5, "Saga", 2)), current = here)
        assertEquals(WorkLink("Part 2 of Saga", null, "Saga"), l.series.single())
        assertEquals("https://archiveofourown.org/users/author65/works".toHttpUrl(), l.authors.single().url)
        val byAuthor = cardLinks(listOf("author65"), listOf(plain), emptyList(), current = "https://archiveofourown.org/users/author65/works".toHttpUrl())
        assertEquals(null, byAuthor.authors.single().url)
    }

    @Test fun `no links at all is empty`() {
        assertTrue(cardLinks(listOf("A"), emptyList(), emptyList()).tappable.not())
        assertTrue(cardLinks(listOf("A"), listOf(AuthorLink("A", "a", "a")), emptyList()).tappable)
    }
}
