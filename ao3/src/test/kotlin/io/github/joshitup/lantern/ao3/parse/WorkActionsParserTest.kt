package io.github.joshitup.lantern.ao3.parse

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkActionsParserTest {
    private fun real(n: String) = javaClass.getResource("/fixtures/real_signed_in/$n")!!.readText()
    /** Hand-authored from real markup; unverified until the plan 2 Task 12 device check. */
    private fun unverified(n: String) = javaClass.getResource("/fixtures/unverified/$n")!!.readText()

    private val plainUrl = "https://archiveofourown.org/works/1000128/chapters/2000169".toHttpUrl()
    private val bookmarkedUrl = "https://archiveofourown.org/works/1000112/chapters/2000104".toHttpUrl()
    private val token = "authenticity_token" to "x"

    @Test fun `plain real page - nothing given, every form present`() {
        val a = WorkActionsParser.parse(real("chapter_plain.html"), plainUrl)
        assertEquals("https://archiveofourown.org/kudos", a.kudos!!.action.toString())
        assertEquals(listOf(token, "kudo[commentable_id]" to "1000128", "kudo[commentable_type]" to "Work"), a.kudos!!.fields)
        assertFalse(a.kudosGiven)

        assertEquals("https://archiveofourown.org/works/1000128/bookmarks", a.bookmarkForm!!.action.toString())
        assertFalse(a.bookmarked)
        assertNull(a.bookmarkEditUrl)

        assertEquals("https://archiveofourown.org/works/1000128/mark_for_later", a.markForLater!!.action.toString())
        assertEquals(listOf("_method" to "patch", token), a.markForLater!!.fields)
        assertFalse(a.markedForLater)

        assertEquals("https://archiveofourown.org/users/owner/subscriptions", a.subscribe!!.action.toString())
        assertEquals(
            listOf(token, "subscription[subscribable_id]" to "1000128", "subscription[subscribable_type]" to "Work"),
            a.subscribe!!.fields,
        )
        assertFalse(a.subscribed)

        assertEquals("https://archiveofourown.org/chapters/2000169/comments", a.comment!!.action.toString())
        assertEquals(
            listOf(token, "comment[pseud_id]" to "1000001", "comment[comment_content]" to "", "controller_name" to "chapters"),
            a.comment!!.fields,
        )
        assertEquals(1000128L, WorkActionsParser.workId(a))
    }

    @Test fun `bookmarked real page - edit form, nothing else given`() {
        val a = WorkActionsParser.parse(real("chapter_bookmarked.html"), bookmarkedUrl)
        assertTrue(a.bookmarked)
        assertEquals("https://archiveofourown.org/bookmarks/3000104", a.bookmarkForm!!.action.toString())
        assertEquals("put", a.bookmarkForm!!.method)
        assertEquals("https://archiveofourown.org/bookmarks/3000104/edit", a.bookmarkEditUrl.toString())
        assertFalse(a.kudosGiven)
        assertFalse(a.markedForLater)
        assertFalse(a.subscribed)
        assertEquals(BookmarkInput("", emptyList(), isPrivate = true, isRec = false), WorkActionsParser.bookmarkInput(a.bookmarkForm!!))
        assertEquals("https://archiveofourown.org/chapters/2000104/comments", a.comment!!.action.toString())
    }

    @Test fun `unverified - kudos given, marked for later, subscribed`() {
        val a = WorkActionsParser.parse(unverified("chapter_kudosed_marked_subscribed.html"), plainUrl)
        assertTrue(a.kudosGiven)
        assertTrue(a.markedForLater)
        assertEquals("https://archiveofourown.org/works/1000128/mark_as_read", a.markForLater!!.action.toString())
        assertTrue(a.subscribed)
        assertEquals("https://archiveofourown.org/users/owner/subscriptions/2000001", a.subscribe!!.action.toString())
        assertEquals("delete", a.subscribe!!.method)
        assertFalse(a.bookmarked)
    }

    @Test fun `kudos given is matched against the signed-in name, case-insensitively`() {
        val html = real("chapter_plain.html").replace("""<a href="/users/reader2">reader2</a>""", """<a href="/users/Owner">Owner</a>""")
        assertTrue(WorkActionsParser.parse(html, plainUrl).kudosGiven)
    }

    @Test fun `unverified - existing bookmark notes, tags and rec`() {
        val a = WorkActionsParser.parse(unverified("chapter_bookmarked_notes_tags_rec.html"), bookmarkedUrl)
        assertEquals(
            BookmarkInput("Loved the ending & the <b>duel</b>.\nRe-read soon.", listOf("slow burn", "re-read", "Favourites"), isPrivate = false, isRec = true),
            WorkActionsParser.bookmarkInput(a.bookmarkForm!!),
        )
    }

    @Test fun `edit link without a form gives the edit url`() {
        val html = """<html><body><div id="greeting"><a href="/users/owner">Hi</a></div>
            <ul class="work navigation actions"><li class="bookmark"><a href="/bookmarks/42/edit">Edit Bookmark</a></li></ul></body></html>"""
        val a = WorkActionsParser.parse(html, plainUrl)
        assertNull(a.bookmarkForm)
        assertTrue(a.bookmarked)
        assertEquals("https://archiveofourown.org/bookmarks/42/edit", a.bookmarkEditUrl.toString())
    }

    @Test fun `bookmark form on an edit page`() {
        val html = real("chapter_bookmarked.html")
        val f = WorkActionsParser.bookmarkForm(html, "https://archiveofourown.org/bookmarks/3000104/edit".toHttpUrl())
        assertEquals("https://archiveofourown.org/bookmarks/3000104", f!!.action.toString())
    }

    @Test fun `real series pages are subscribed`() {
        for ((name, id) in listOf("series_a.html" to "4000101", "series_b.html" to "4000105")) {
            val l = WorkActionsParser.parseList(real(name), "https://archiveofourown.org/series/$id".toHttpUrl())
            assertTrue(name, l.subscribed)
            assertEquals("delete", l.subscribe!!.method)
            assertTrue(l.subscribe!!.fields.contains("subscription[subscribable_type]" to "Series"))
            assertTrue(l.subscribe!!.fields.contains("subscription[subscribable_id]" to id))
        }
    }

    @Test fun `real author works pages hold no subscribe form`() {
        for (n in listOf("user_works_p1.html", "user_works_p2.html", "user_works_small.html")) {
            val l = WorkActionsParser.parseList(real(n), "https://archiveofourown.org/users/someone/works".toHttpUrl())
            assertNull(n, l.subscribe)
            assertFalse(l.subscribed)
        }
    }

    @Test fun `unverified - author dashboard subscribe form`() {
        val l = WorkActionsParser.parseList(unverified("user_dashboard.html"), "https://archiveofourown.org/users/author60".toHttpUrl())
        assertFalse(l.subscribed)
        assertEquals("https://archiveofourown.org/users/owner/subscriptions", l.subscribe!!.action.toString())
        assertTrue(l.subscribe!!.fields.contains("subscription[subscribable_type]" to "User"))
    }

    @Test fun `a chapter page is not a list subscribe page`() {
        assertNull(WorkActionsParser.parseList(real("chapter_plain.html"), plainUrl).subscribe)
    }

    @Test fun `action errors - flash errors, kudos already left`() {
        assertNull(WorkActionsParser.actionError(real("chapter_plain.html")))
        val already = unverified("kudos_already_left.html")
        assertEquals("You have already left kudos here. :)", WorkActionsParser.actionError(already))
        assertTrue(WorkActionsParser.kudosAlreadyLeft(already))
        assertFalse(WorkActionsParser.kudosAlreadyLeft(unverified("stale_token.html")))
        assertNotNull(WorkActionsParser.actionError(unverified("stale_token.html")))
        assertEquals("Nope", WorkActionsParser.actionError("""<div class="flash comment_error">Nope</div>"""))
    }

    @Test fun `success notices - non-error flashes only`() {
        assertTrue(WorkActionsParser.successNotice("""<div class="flash comment_notice">Comment created!</div>""", "Comment created"))
        assertFalse(WorkActionsParser.successNotice("""<div class="flash comment_error">Comment created? no</div>""", "Comment created"))
        assertFalse(WorkActionsParser.successNotice(real("chapter_plain.html"), "Comment created", "Thank you for leaving kudos"))
    }

    @Test fun `subscribe page for a list url`() {
        assertEquals("https://archiveofourown.org/series/5", Ao3Urls.subscribePage("https://archiveofourown.org/series/5?page=2".toHttpUrl()).toString())
        assertEquals("https://archiveofourown.org/users/a", Ao3Urls.subscribePage("https://archiveofourown.org/users/a/works".toHttpUrl()).toString())
        assertEquals("https://archiveofourown.org/users/a", Ao3Urls.subscribePage("https://archiveofourown.org/users/a/pseuds/p/works".toHttpUrl()).toString())
        assertNull(Ao3Urls.subscribePage("https://archiveofourown.org/works/5".toHttpUrl()))
    }
}
