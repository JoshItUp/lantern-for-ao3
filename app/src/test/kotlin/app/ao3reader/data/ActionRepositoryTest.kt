package app.ao3reader.data

import android.content.Context
import app.ao3reader.account.CredentialStore
import app.ao3reader.account.FakeSecretBox
import app.ao3reader.account.Session
import app.ao3reader.ao3.net.FetchResult
import app.ao3reader.ao3.net.Fetcher
import app.ao3reader.ao3.net.HttpStatusException
import app.ao3reader.ao3.parse.Ao3Urls
import app.ao3reader.ao3.parse.BookmarkInput
import app.ao3reader.ui.common.WorkAction
import app.ao3reader.ui.common.actionFeedback
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class ActionRepositoryTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val fake = FakeFetcher()
    private fun real(n: String) = File("../ao3/src/test/resources/fixtures/real_signed_in/$n").readText()
    /** Hand-authored from real markup; unverified until the plan 2 Task 12 device check. */
    private fun unverified(n: String) = File("../ao3/src/test/resources/fixtures/unverified/$n").readText()

    private val work = 1000128L
    private val page = "https://archiveofourown.org/works/1000128/chapters/2000169?view_adult=true".toHttpUrl()
    private val bmWork = 1000112L
    private val bmPage = "https://archiveofourown.org/works/1000112/chapters/2000104?view_adult=true".toHttpUrl()
    private val seriesUrl = "https://archiveofourown.org/series/4000101".toHttpUrl()
    private val authorWorks = "https://archiveofourown.org/users/author60/works".toHttpUrl()
    private val authorPage = "https://archiveofourown.org/users/author60".toHttpUrl()

    /** Posts wait on [postGate] when set (an in-flight request). */
    private var postGate: CompletableDeferred<Unit>? = null
    private val gated = object : Fetcher by fake {
        override suspend fun post(url: HttpUrl, fields: List<Pair<String, String>>, accept: String?, interactive: Boolean): FetchResult {
            val r = fake.post(url, fields, accept, interactive)
            postGate?.await()
            return r
        }
    }

    private fun TestScope.repo(signedIn: Boolean = true): ActionRepository {
        val prefs = ctx.getSharedPreferences("act-test-${System.nanoTime()}", Context.MODE_PRIVATE)
        val store = CredentialStore(prefs, FakeSecretBox())
        if (signedIn) { store.save("owner", "pw"); store.saveDisplayName("owner") }
        session = Session(gated, store, {}, backgroundScope)
        return ActionRepository(session, backgroundScope)
    }

    private lateinit var session: Session
    private val kudosedPage get() = unverified("chapter_kudosed_marked_subscribed.html")
    /** The plain work, now bookmarked: its bookmark form is the edit form (`/bookmarks/555`, put). */
    private fun plainBookmarked(private: Boolean, rec: Boolean) = checkboxes(
        real("chapter_plain.html").replace(
            """<form action="/works/1000128/bookmarks" accept-charset="UTF-8" method="post">""",
            """<form action="/bookmarks/555" accept-charset="UTF-8" method="post"><input type="hidden" name="_method" value="put" />""",
        ), private, rec,
    )
    private fun checkboxes(html: String, private: Boolean, rec: Boolean) = html
        .replace(""" checked="checked" name="bookmark[private]"""", """ name="bookmark[private]"""")
        .replace(""" checked="checked" name="bookmark[rec]"""", """ name="bookmark[rec]"""")
        .replace("""value="1" name="bookmark[private]"""", if (private) """value="1" checked="checked" name="bookmark[private]"""" else """value="1" name="bookmark[private]"""")
        .replace("""value="1" name="bookmark[rec]"""", if (rec) """value="1" checked="checked" name="bookmark[rec]"""" else """value="1" name="bookmark[rec]"""")
    private val greetingOnly = "<html><body><header id=\"header\"><div id=\"greeting\"><a href=\"/users/owner\">Hi</a></div></header>Elsewhere</body></html>"
    private fun causeOf(l: Loaded<*>) = ((l as Loaded.Error).result as FetchResult.Failed).cause

    private fun ok(html: String, url: HttpUrl = page) = FetchResult.Ok(html, url)
    private fun tokenOf(fields: List<Pair<String, String>>) = fields.first { it.first == "authenticity_token" }.second
    private fun withToken(html: String, t: String) = html.replace("name=\"authenticity_token\" value=\"x\"", "name=\"authenticity_token\" value=\"$t\"")
    private fun posted(i: Int = 0) = fake.posts[i]

    // ---- kudos ----

    @Test fun `kudos posts the kudos form and marks kudos given`() = runTest {
        val r = repo()
        r.update(work, real("chapter_plain.html"), page)
        fake.postResponse = { _, _ -> ok(kudosedPage) }
        assertEquals(Loaded.Ok(Unit), r.kudos(work))
        assertEquals("https://archiveofourown.org/kudos", posted().first)
        assertEquals(listOf("authenticity_token" to "x", "kudo[commentable_id]" to "1000128", "kudo[commentable_type]" to "Work"), posted().second)
        assertTrue(r.state.value[work]!!.kudosGiven)
        assertTrue(fake.calls.isEmpty())
    }

    @Test fun `kudos already given is success`() = runTest {
        val r = repo()
        r.update(work, real("chapter_plain.html"), page)
        fake.postResponse = { _, _ -> ok(unverified("kudos_already_left.html")) }
        assertEquals(Loaded.Ok(Unit), r.kudos(work))
        assertEquals(1, fake.posts.size)
        assertTrue(fake.calls.isEmpty())
        assertTrue(r.state.value[work]!!.kudosGiven)
    }

    @Test fun `kudos already shown on the page sends nothing`() = runTest {
        val r = repo()
        r.update(work, unverified("chapter_kudosed_marked_subscribed.html"), page)
        assertEquals(Loaded.Ok(Unit), r.kudos(work))
        assertTrue(fake.posts.isEmpty())
    }

    // ---- stale token / retry rule ----

    @Test fun `stale token refreshes once`() = runTest {
        val r = repo()
        r.update(work, real("chapter_plain.html"), page)
        fake.ok(page, withToken(real("chapter_plain.html"), "fresh"))
        fake.postResponse = { _, f -> if (tokenOf(f) == "x") ok(unverified("stale_token.html")) else ok(kudosedPage) }
        assertEquals(Loaded.Ok(Unit), r.kudos(work))
        assertEquals(listOf("x", "fresh"), fake.posts.map { tokenOf(it.second) })
        assertEquals(listOf(page.toString()), fake.calls)
    }

    @Test fun `stale token gives up after one retry`() = runTest {
        val r = repo()
        r.update(work, real("chapter_plain.html"), page)
        fake.ok(page, real("chapter_plain.html"))
        fake.postResponse = { _, _ -> ok(unverified("stale_token.html")) }
        val res = r.kudos(work)
        assertTrue(res is Loaded.Error)
        assertEquals(2, fake.posts.size)
        assertEquals(1, fake.calls.size)
    }

    @Test fun `422 and Blocked are retried once with a fresh form`() = runTest {
        for (first in listOf(FetchResult.Failed(HttpStatusException(422)), FetchResult.Blocked)) {
            fake.posts.clear(); fake.calls.clear()
            val r = repo()
            r.update(work, real("chapter_plain.html"), page)
            fake.ok(page, withToken(real("chapter_plain.html"), "fresh"))
            fake.postResponse = { _, f -> if (tokenOf(f) == "x") first else ok(unverified("chapter_kudosed_marked_subscribed.html")) }
            assertEquals(first.toString(), Loaded.Ok(true), r.toggleSubscribe(work))
            assertEquals(listOf("x", "fresh"), fake.posts.map { tokenOf(it.second) })
        }
    }

    @Test fun `a network failure is not retried`() = runTest {
        val r = repo()
        r.update(work, real("chapter_plain.html"), page)
        fake.postResponse = { _, _ -> FetchResult.Failed(IOException("reset")) }
        val res = r.kudos(work)
        assertTrue(causeOf(res) is ActionMayHaveTakenEffectException)
        assertEquals(1, fake.posts.size)
        assertTrue(fake.calls.isEmpty())
    }

    @Test fun `signed-out post response renews the session, re-GETs and resubmits`() = runTest {
        val r = repo()
        r.update(work, real("chapter_plain.html"), page)
        fake.ok(Ao3Urls.login(), File("../ao3/src/test/resources/fixtures/login_page.html").readText())
        fake.ok(page, withToken(real("chapter_plain.html"), "fresh"))
        val signedOut = """<html><body><header id="header"><div id="login"><a href="/users/login">Log In</a></div></header></body></html>"""
        fake.postResponse = { url, f ->
            when {
                url == Ao3Urls.login().toString() -> ok(real("chapter_plain.html"))
                tokenOf(f) == "x" -> ok(signedOut)
                else -> ok(kudosedPage)
            }
        }
        assertEquals(Loaded.Ok(Unit), r.kudos(work))
        val kudosPosts = fake.posts.filter { it.first.endsWith("/kudos") }
        assertEquals(listOf("x", "fresh"), kudosPosts.map { tokenOf(it.second) })
    }

    @Test fun `known signed out with re-login unavailable posts nothing and asks to sign in`() = runTest {
        val r = repo()
        r.update(work, real("chapter_plain.html"), page)
        fake.responses[Ao3Urls.login().toString()] = FetchResult.RateLimited(60)
        session.observe("""<html><body><header id="header"><div id="login"><a href="/users/login">Log In</a></div></header></body></html>""")
        fake.postResponse = { _, _ -> ok(kudosedPage) }
        val res = r.kudos(work)
        assertTrue(causeOf(res) is NotSignedInException)
        assertTrue(fake.posts.isEmpty())
        assertFalse(r.state.value[work]?.kudosGiven ?: false)
        assertEquals("Sign in again to do that.", actionFeedback(WorkAction.KUDOS, res).text)
    }

    @Test fun `retry finds the action already in effect and does not post again`() = runTest {
        val r = repo()
        r.update(work, real("chapter_plain.html"), page)
        fake.ok(page, unverified("chapter_kudosed_marked_subscribed.html"))
        fake.postResponse = { _, _ -> FetchResult.Blocked }
        assertEquals(Loaded.Ok(true), r.toggleSubscribe(work))
        assertEquals(1, fake.posts.size)
        assertTrue(r.state.value[work]!!.subscribed)
    }

    // ---- toggles ----

    @Test fun `mark for later and subscribe post their forms`() = runTest {
        val r = repo()
        r.update(work, real("chapter_plain.html"), page)
        fake.postResponse = { _, _ -> ok(unverified("chapter_kudosed_marked_subscribed.html")) }
        assertEquals(Loaded.Ok(true), r.toggleMarkForLater(work))
        assertEquals("https://archiveofourown.org/works/1000128/mark_for_later", posted(0).first)
        assertEquals(listOf("_method" to "patch", "authenticity_token" to "x"), posted(0).second)
        assertTrue(r.state.value[work]!!.markedForLater)
        // The response page now holds the Mark as Read and Unsubscribe forms.
        fake.postResponse = { _, _ -> ok(real("chapter_plain.html")) }
        assertEquals(Loaded.Ok(false), r.toggleSubscribe(work))
        assertEquals("https://archiveofourown.org/users/owner/subscriptions/2000001", posted(1).first)
        assertEquals("_method" to "delete", posted(1).second.first())
        assertFalse(r.state.value[work]!!.subscribed)
    }

    @Test fun `subscribe posts the create form`() = runTest {
        val r = repo()
        r.update(work, real("chapter_plain.html"), page)
        fake.postResponse = { _, _ -> ok(unverified("chapter_kudosed_marked_subscribed.html")) }
        assertEquals(Loaded.Ok(true), r.toggleSubscribe(work))
        assertEquals("https://archiveofourown.org/users/owner/subscriptions", posted().first)
        assertEquals(
            listOf("authenticity_token" to "x", "subscription[subscribable_id]" to "1000128", "subscription[subscribable_type]" to "Work"),
            posted().second,
        )
    }

    @Test fun `toggle debounces and rolls back`() = runTest {
        val r = repo()
        r.update(work, real("chapter_plain.html"), page)
        fake.postResponse = { _, _ -> FetchResult.Failed(IOException("reset")) }
        postGate = CompletableDeferred()
        val first = async { r.toggleMarkForLater(work) }
        runCurrent()
        assertTrue("optimistic", r.state.value[work]!!.markedForLater)
        assertEquals("second tap ignored", Loaded.Ok(true), r.toggleMarkForLater(work))
        assertEquals(1, fake.posts.size)
        postGate!!.complete(Unit)
        assertTrue(first.await() is Loaded.Error)
        assertFalse("rolled back", r.state.value[work]!!.markedForLater)
        assertEquals(1, fake.posts.size)
    }

    @Test fun `an answer that is not the work page is confirmed by a re-GET`() = runTest {
        val r = repo()
        r.update(work, real("chapter_plain.html"), page)
        fake.ok(page, kudosedPage)
        fake.postResponse = { _, _ -> ok(greetingOnly) }
        assertEquals(Loaded.Ok(true), r.toggleMarkForLater(work))
        assertEquals(listOf(page.toString()), fake.calls)
        // The re-GET brought the Mark as Read form.
        fake.postResponse = { _, _ -> ok(real("chapter_plain.html")) }
        assertEquals(Loaded.Ok(false), r.toggleMarkForLater(work))
        assertEquals("https://archiveofourown.org/works/1000128/mark_as_read", posted(1).first)
        assertEquals(1, fake.calls.size)
    }

    @Test fun `a 2xx the re-GET doesn't confirm is unconfirmed, not success`() = runTest {
        val r = repo()
        r.update(work, real("chapter_plain.html"), page)
        fake.ok(page, real("chapter_plain.html"))
        fake.postResponse = { _, _ -> ok(greetingOnly) }
        val res = r.toggleMarkForLater(work)
        assertTrue(causeOf(res) is ActionUnconfirmedException)
        assertEquals(1, fake.posts.size)
        assertFalse(r.state.value[work]!!.markedForLater)
    }

    @Test fun `own work page that doesn't show the change is unconfirmed`() = runTest {
        val r = repo()
        r.update(work, real("chapter_plain.html"), page)
        fake.postResponse = { _, _ -> ok(real("chapter_plain.html")) }
        assertTrue(causeOf(r.kudos(work)) is ActionUnconfirmedException)
        assertTrue(fake.calls.isEmpty())
    }

    @Test fun `kudos thank-you notice confirms without a re-GET`() = runTest {
        val r = repo()
        r.update(work, real("chapter_plain.html"), page)
        fake.postResponse = { _, _ -> ok(greetingOnly.replace("Elsewhere", "<div class=\"flash comment_notice\">Thank you for leaving kudos!</div>")) }
        assertEquals(Loaded.Ok(Unit), r.kudos(work))
        assertTrue(fake.calls.isEmpty())
        assertTrue(r.state.value[work]!!.kudosGiven)
    }

    @Test fun `kudos stay given when a re-GET shows a truncated list`() = runTest {
        val r = repo()
        r.update(work, real("chapter_plain.html"), page)
        fake.postResponse = { _, _ -> ok(kudosedPage) }
        assertEquals(Loaded.Ok(Unit), r.kudos(work))
        r.update(work, real("chapter_plain.html"), page)
        assertTrue(r.state.value[work]!!.kudosGiven)
        fake.ok(page, real("chapter_plain.html"))
        assertTrue((r.refresh(work) as Loaded.Ok).value.kudosGiven)
    }

    @Test fun `failure after sending marks stale and the next toggle reads the server first`() = runTest {
        val r = repo()
        r.update(work, real("chapter_plain.html"), page)
        fake.postResponse = { _, _ -> FetchResult.Failed(HttpStatusException(502)) }
        assertTrue(causeOf(r.toggleMarkForLater(work)) is ActionMayHaveTakenEffectException)
        assertFalse(r.state.value[work]!!.markedForLater)
        // It did go through: the next toggle GETs first and unmarks instead of marking again.
        fake.ok(page, kudosedPage)
        fake.postResponse = { _, _ -> ok(real("chapter_plain.html")) }
        assertEquals(Loaded.Ok(false), r.toggleMarkForLater(work))
        assertEquals(listOf(page.toString()), fake.calls)
        assertEquals("https://archiveofourown.org/works/1000128/mark_as_read", posted(1).first)
    }

    @Test fun `cancelling during the POST still records the outcome`() = runTest {
        val r = repo()
        r.update(work, real("chapter_plain.html"), page)
        fake.postResponse = { _, _ -> FetchResult.Failed(IOException("reset")) }
        postGate = CompletableDeferred()
        val job = async { r.toggleSubscribe(work) }
        runCurrent()
        job.cancel()
        postGate!!.complete(Unit)
        runCurrent()
        fake.ok(page, real("chapter_plain.html"))
        fake.postResponse = { _, _ -> ok(kudosedPage) }
        postGate = null
        assertEquals(Loaded.Ok(true), r.toggleSubscribe(work))
        assertEquals("stale: re-read before acting", listOf(page.toString()), fake.calls)
    }

    @Test fun `unknown work is fetched first`() = runTest {
        val r = repo()
        val workUrl = Ao3Urls.chapter(work, null)
        fake.ok(workUrl, real("chapter_plain.html"))
        fake.postResponse = { _, _ -> ok(unverified("chapter_kudosed_marked_subscribed.html")) }
        assertEquals(Loaded.Ok(true), r.toggleSubscribe(work))
        assertEquals(listOf(workUrl.toString()), fake.calls)
    }

    // ---- series and author subscribe ----

    private val unsubscribedSeries get() = real("series_a.html")
        .replace("""<input type="hidden" name="_method" value="delete" autocomplete="off" />""", "")
        .replace("/users/owner/subscriptions/1\"", "/users/owner/subscriptions\"")

    @Test fun `series unsubscribe posts the delete form`() = runTest {
        val r = repo()
        r.updateList(seriesUrl, real("series_a.html"), seriesUrl)
        assertTrue(r.listState.value[seriesUrl.toString()]!!.subscribed)
        fake.postResponse = { _, _ -> ok(unsubscribedSeries, seriesUrl) }
        assertEquals(Loaded.Ok(false), r.toggleListSubscribe(seriesUrl))
        assertEquals("https://archiveofourown.org/users/owner/subscriptions/1", posted().first)
        assertEquals(
            listOf("_method" to "delete", "authenticity_token" to "x", "subscription[subscribable_id]" to "4000101", "subscription[subscribable_type]" to "Series"),
            posted().second,
        )
        assertFalse(r.listState.value[seriesUrl.toString()]!!.subscribed)
    }

    @Test fun `author subscribe GETs the user page and posts the create form`() = runTest {
        val r = repo()
        fake.ok(authorPage, unverified("user_dashboard.html"))
        val subscribed = unverified("user_dashboard.html").replace(
            """action="/users/owner/subscriptions" accept-charset="UTF-8" method="post">""",
            """action="/users/owner/subscriptions/77" accept-charset="UTF-8" method="post"><input type="hidden" name="_method" value="delete" />""",
        )
        fake.postResponse = { _, _ -> fake.ok(authorPage, subscribed); ok(greetingOnly, authorPage) }
        assertEquals(Loaded.Ok(true), r.toggleListSubscribe(authorWorks))
        assertEquals(listOf(authorPage.toString(), authorPage.toString()), fake.calls)
        assertEquals("https://archiveofourown.org/users/owner/subscriptions", posted().first)
        assertTrue(posted().second.contains("subscription[subscribable_type]" to "User"))
        assertTrue(r.listState.value[authorPage.toString()]!!.subscribed)
        // A works page has no form: updating from it keeps what is known.
        r.updateList(authorWorks, real("user_works_small.html"), authorWorks)
        assertTrue(r.listState.value[authorPage.toString()]!!.subscribed)
    }

    @Test fun `list toggle debounces and rolls back`() = runTest {
        val r = repo()
        r.updateList(seriesUrl, real("series_a.html"), seriesUrl)
        fake.postResponse = { _, _ -> FetchResult.RateLimited(30) }
        postGate = CompletableDeferred()
        val first = async { r.toggleListSubscribe(seriesUrl) }
        runCurrent()
        assertFalse("optimistic", r.listState.value[seriesUrl.toString()]!!.subscribed)
        assertEquals(Loaded.Ok(false), r.toggleListSubscribe(seriesUrl))
        postGate!!.complete(Unit)
        assertTrue(first.await() is Loaded.Error)
        assertTrue("rolled back", r.listState.value[seriesUrl.toString()]!!.subscribed)
        assertEquals(1, fake.posts.size)
    }

    @Test fun `list stale token refreshes once`() = runTest {
        val r = repo()
        r.updateList(seriesUrl, real("series_a.html"), seriesUrl)
        fake.ok(seriesUrl, withToken(real("series_a.html"), "fresh"))
        fake.postResponse = { _, f -> if (tokenOf(f) == "x") FetchResult.Blocked else ok(unsubscribedSeries, seriesUrl) }
        assertEquals(Loaded.Ok(false), r.toggleListSubscribe(seriesUrl))
        assertEquals(listOf("x", "fresh"), fake.posts.map { tokenOf(it.second) })
    }

    @Test fun `non-list url is rejected without a request`() = runTest {
        val r = repo()
        assertTrue(r.toggleListSubscribe(page) is Loaded.Error)
        assertTrue(fake.posts.isEmpty() && fake.calls.isEmpty())
    }

    // ---- bookmarks ----

    @Test fun `create bookmark posts notes, tags and checkbox pairs`() = runTest {
        val r = repo()
        r.update(work, real("chapter_plain.html"), page)
        fake.postResponse = { _, _ -> ok(greetingOnly, "https://archiveofourown.org/bookmarks/555".toHttpUrl()) }
        fake.ok(page, plainBookmarked(private = true, rec = false))
        val res = r.saveBookmark(work, BookmarkInput("Great & <b>fun</b>", listOf("slow burn", "fluff"), isPrivate = true, isRec = false))
        assertEquals(Loaded.Ok(Unit), res)
        assertEquals("https://archiveofourown.org/works/1000128/bookmarks", posted().first)
        assertEquals(
            listOf(
                "authenticity_token" to "x", "bookmark[pseud_id]" to "1000001",
                "bookmark[bookmarker_notes]" to "Great & <b>fun</b>", "bookmark[tag_string]" to "slow burn, fluff",
                "bookmark[collection_names]" to "",
                "bookmark[private]" to "0", "bookmark[private]" to "1",
                "bookmark[rec]" to "0",
            ),
            posted().second,
        )
    }

    @Test fun `edit bookmark posts the edit form with put`() = runTest {
        val r = repo()
        r.update(bmWork, real("chapter_bookmarked.html"), bmPage)
        assertEquals(BookmarkInput("", emptyList(), isPrivate = true, isRec = false), r.existingBookmark(bmWork))
        fake.postResponse = { _, _ -> ok(checkboxes(real("chapter_bookmarked.html"), private = false, rec = true), bmPage) }
        assertEquals(Loaded.Ok(Unit), r.saveBookmark(bmWork, BookmarkInput("n", emptyList(), isPrivate = false, isRec = true)))
        assertEquals("https://archiveofourown.org/bookmarks/3000104", posted().first)
        val f = posted().second
        assertEquals("_method" to "put", f.first())
        assertEquals(listOf("0"), f.filter { it.first == "bookmark[private]" }.map { it.second })
        assertEquals(listOf("0", "1"), f.filter { it.first == "bookmark[rec]" }.map { it.second })
        assertEquals(listOf("n"), f.filter { it.first == "bookmark[bookmarker_notes]" }.map { it.second })
    }

    @Test fun `existing bookmark prefill from notes, tags and rec`() = runTest {
        val r = repo()
        r.update(bmWork, unverified("chapter_bookmarked_notes_tags_rec.html"), bmPage)
        assertEquals(
            BookmarkInput("Loved the ending & the <b>duel</b>.\nRe-read soon.", listOf("slow burn", "re-read", "Favourites"), isPrivate = false, isRec = true),
            r.existingBookmark(bmWork),
        )
        r.update(work, real("chapter_plain.html"), page)
        assertNull(r.existingBookmark(work))
    }

    @Test fun `edit link only GETs the edit page for the form`() = runTest {
        val r = repo()
        val linkOnly = """<html><body><header id="header"><div id="greeting"><a href="/users/owner">Hi</a></div></header>
            <ul class="work navigation actions"><li class="bookmark"><a href="/bookmarks/3000104/edit">Edit Bookmark</a></li></ul></body></html>"""
        r.update(bmWork, linkOnly, bmPage)
        val edit = "https://archiveofourown.org/bookmarks/3000104/edit".toHttpUrl()
        fake.ok(edit, real("chapter_bookmarked.html"))
        fake.postResponse = { _, _ -> ok(real("chapter_bookmarked.html"), bmPage) }
        assertEquals(Loaded.Ok(Unit), r.saveBookmark(bmWork, BookmarkInput("", emptyList(), isPrivate = true, isRec = false)))
        assertEquals(listOf(edit.toString()), fake.calls)
        assertEquals("https://archiveofourown.org/bookmarks/3000104", posted().first)
    }

    @Test fun `create refused, then found bookmarked - the input is saved through the edit form`() = runTest {
        val r = repo()
        r.update(work, real("chapter_plain.html"), page)
        fake.ok(page, plainBookmarked(private = false, rec = false))
        fake.postResponse = { url, _ -> if (url.endsWith("/works/1000128/bookmarks")) FetchResult.Blocked else ok(plainBookmarked(private = true, rec = false)) }
        assertEquals(Loaded.Ok(Unit), r.saveBookmark(work, BookmarkInput("mine", listOf("a"), isPrivate = true, isRec = false)))
        assertEquals(2, fake.posts.size)
        assertEquals("https://archiveofourown.org/bookmarks/555", posted(1).first)
        assertEquals("_method" to "put", posted(1).second.first())
        assertTrue(posted(1).second.contains("bookmark[bookmarker_notes]" to "mine"))
    }

    @Test fun `bookmark not shown after saving is unconfirmed`() = runTest {
        val r = repo()
        r.update(work, real("chapter_plain.html"), page)
        fake.ok(page, real("chapter_plain.html"))
        fake.postResponse = { _, _ -> ok(greetingOnly) }
        assertTrue(causeOf(r.saveBookmark(work, BookmarkInput("", emptyList(), isPrivate = false, isRec = false))) is ActionUnconfirmedException)
        assertEquals(1, fake.posts.size)
    }

    // ---- comments ----

    @Test fun `comments reject blank text with no request`() = runTest {
        val r = repo()
        r.update(work, real("chapter_plain.html"), page)
        for (t in listOf("", "   ", "\n\t ")) assertTrue(r.comment(work, 2000169L, t) is Loaded.Error)
        assertTrue(fake.posts.isEmpty() && fake.calls.isEmpty())
    }

    @Test fun `comment posts the text as-is with the pseud`() = runTest {
        val r = repo()
        r.update(work, real("chapter_plain.html"), page)
        fake.postResponse = { _, _ -> ok(real("chapter_plain.html")) }
        val text = "  Loved it!\n<i>more</i> "
        assertEquals(Loaded.Ok(Unit), r.comment(work, 2000169L, text))
        assertEquals("https://archiveofourown.org/chapters/2000169/comments", posted().first)
        assertEquals(
            listOf("authenticity_token" to "x", "comment[pseud_id]" to "1000001", "comment[comment_content]" to text, "controller_name" to "chapters"),
            posted().second,
        )
    }

    @Test fun `comment on another chapter GETs that chapter's form`() = runTest {
        val r = repo()
        r.update(bmWork, real("chapter_bookmarked.html"), bmPage)
        val other = Ao3Urls.chapter(work, 2000169L)
        fake.ok(other, real("chapter_plain.html"))
        fake.postResponse = { _, _ -> ok(real("chapter_plain.html")) }
        assertEquals(Loaded.Ok(Unit), r.comment(work, 2000169L, "hi"))
        assertEquals(listOf(other.toString()), fake.calls)
        assertEquals("https://archiveofourown.org/chapters/2000169/comments", posted().first)
    }

    @Test fun `comment flash error is reported after one retry`() = runTest {
        val r = repo()
        r.update(work, real("chapter_plain.html"), page)
        fake.ok(page, real("chapter_plain.html"))
        fake.postResponse = { _, _ -> ok(real("chapter_plain.html").replace("<div class=\"flash\"></div>", "<div class=\"flash comment_error\">Too long</div>")) }
        val res = r.comment(work, 2000169L, "hi")
        assertTrue(res is Loaded.Error)
        assertEquals(2, fake.posts.size)
    }

    // ---- account ----

    @Test fun `signed out - no request`() = runTest {
        val r = repo(signedIn = false)
        assertTrue(r.kudos(work) is Loaded.Error)
        assertTrue(r.toggleSubscribe(work) is Loaded.Error)
        assertTrue(r.toggleListSubscribe(seriesUrl) is Loaded.Error)
        assertTrue(r.comment(work, null, "hi") is Loaded.Error)
        assertTrue(fake.posts.isEmpty() && fake.calls.isEmpty())
    }

    @Test fun `update ignores signed-out pages and other accounts`() = runTest {
        val r = repo()
        r.update(work, File("../ao3/src/test/resources/fixtures/chapter_signed_out.html").readText(), page)
        r.update(work, real("chapter_plain.html").replace("""<a href="/users/owner">""", """<a href="/users/someone">"""), page)
        assertNull(r.state.value[work])
        r.update(work, real("chapter_plain.html"), page)
        assertFalse(r.state.value[work]!!.kudosGiven)
        r.clear()
        assertTrue(r.state.value.isEmpty())
    }

    @Test fun `comment answered by another page without a notice is unconfirmed`() = runTest {
        val r = repo()
        r.update(work, real("chapter_plain.html"), page)
        fake.postResponse = { _, _ -> ok(greetingOnly) }
        assertTrue(causeOf(r.comment(work, 2000169L, "hi")) is ActionUnconfirmedException)
        assertEquals(1, fake.posts.size)
        assertTrue("never re-GETs to resubmit", fake.calls.isEmpty())
    }

    @Test fun `comment created notice confirms`() = runTest {
        val r = repo()
        r.update(work, real("chapter_plain.html"), page)
        fake.postResponse = { _, _ -> ok(greetingOnly.replace("Elsewhere", "<div class=\"flash comment_notice\">Comment created!</div>")) }
        assertEquals(Loaded.Ok(Unit), r.comment(work, 2000169L, "hi"))
    }

    @Test fun `comment failing after sending may have been posted and marks stale`() = runTest {
        val r = repo()
        r.update(work, real("chapter_plain.html"), page)
        fake.postResponse = { _, _ -> FetchResult.Failed(IOException("timeout")) }
        assertTrue(causeOf(r.comment(work, 2000169L, "hi")) is ActionMayHaveTakenEffectException)
        assertEquals(1, fake.posts.size)
        fake.ok(page, real("chapter_plain.html"))
        fake.postResponse = { _, _ -> ok(real("chapter_plain.html")) }
        assertEquals(Loaded.Ok(Unit), r.comment(work, 2000169L, "hi again"))
        assertEquals(1, fake.calls.size)
    }

    @Test fun `list re-GET updates the list key, not the page url`() = runTest {
        val r = repo()
        val page2 = "https://archiveofourown.org/series/4000101?page=2".toHttpUrl()
        r.updateList(seriesUrl, real("series_a.html"), page2)
        fake.ok(page2, withToken(real("series_a.html"), "fresh"))
        fake.postResponse = { _, f -> if (tokenOf(f) == "x") FetchResult.Blocked else ok(unsubscribedSeries, seriesUrl) }
        assertEquals(Loaded.Ok(false), r.toggleListSubscribe(seriesUrl))
        assertEquals(listOf(page2.toString()), fake.calls)
        assertEquals(setOf(seriesUrl.toString()), r.listState.value.keys)
        assertFalse(r.listState.value[seriesUrl.toString()]!!.subscribed)
    }

    @Test fun `list keys lower-case the user name`() = runTest {
        assertEquals(
            "https://archiveofourown.org/users/author60",
            ActionRepository.listKey("https://archiveofourown.org/users/Author60/pseuds/PseudX/works".toHttpUrl()),
        )
        assertEquals(seriesUrl.toString(), ActionRepository.listKey(seriesUrl))
        val r = repo()
        r.updateList("https://archiveofourown.org/users/Author60".toHttpUrl(), unverified("user_dashboard.html"), authorPage)
        assertTrue(r.listState.value.containsKey(authorPage.toString()))
    }

    @Test fun `state is cleared when the acting account changes`() = runTest {
        val r = repo()
        r.update(work, real("chapter_plain.html"), page)
        assertTrue(r.state.value.containsKey(work))
        session.observe(real("chapter_plain.html").replace("""<a href="/users/owner">""", """<a href="/users/someone">"""))
        runCurrent()
        assertTrue(r.state.value.isEmpty())
    }

    @Test fun `kudos confirmed after the account changed never mark the new account's work`() = runTest {
        val r = repo()
        r.update(work, real("chapter_plain.html"), page)
        // No site header: the answer must not itself tell the session who is signed in.
        val thanks = "<html><body><div class=\"flash comment_notice\">Thank you for leaving kudos!</div></body></html>"
        fake.postResponse = { _, _ -> ok(thanks) }
        postGate = CompletableDeferred()
        val inFlight = async { r.kudos(work) }
        runCurrent()
        val someone = real("chapter_plain.html").replace("""<a href="/users/owner">""", """<a href="/users/someone">""")
        session.observe(someone)
        runCurrent()
        postGate!!.complete(Unit)
        inFlight.await()
        postGate = null
        r.update(work, someone, page)
        assertFalse("not inherited by the new account", r.state.value[work]!!.kudosGiven)
        fake.postResponse = { _, _ -> ok(thanks) }
        assertEquals(Loaded.Ok(Unit), r.kudos(work))
        assertEquals("the new account really sends its kudos", 2, fake.posts.size)
    }
}
