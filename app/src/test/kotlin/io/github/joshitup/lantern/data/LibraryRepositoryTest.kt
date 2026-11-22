package io.github.joshitup.lantern.data

import android.content.Context
import androidx.room.Room
import io.github.joshitup.lantern.account.CredentialStore
import io.github.joshitup.lantern.account.FakeSecretBox
import io.github.joshitup.lantern.account.Session
import io.github.joshitup.lantern.ao3.net.FetchResult
import io.github.joshitup.lantern.ao3.parse.Ao3Urls
import io.github.joshitup.lantern.ui.account.asAccountSource
import io.github.joshitup.lantern.data.db.AppDatabase
import io.github.joshitup.lantern.data.db.LibraryPageEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LibraryRepositoryTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
    private val fake = FakeFetcher()
    private fun fixture(name: String) = File("../ao3/src/test/resources/fixtures/real_signed_in/$name").readText()

    @After fun tearDown() { db.close() }

    private fun TestScope.repo(signedIn: Boolean = true, user: String = "owner") = session(signedIn, user).second

    private fun TestScope.session(signedIn: Boolean = true, user: String = "owner"): Pair<Session, LibraryRepository> {
        val prefs = ctx.getSharedPreferences("lib-test-${System.nanoTime()}", Context.MODE_PRIVATE)
        val store = CredentialStore(prefs, FakeSecretBox())
        if (signedIn) { store.save(user, "pw"); store.saveDisplayName(user) }
        val session = Session(fake, store, {}, backgroundScope)
        val d = StandardTestDispatcher(testScheduler)
        return session to LibraryRepository(session, db, { 42L }, d, d)
    }

    @Test fun `load caches page 1 and upserts read marks for history`() = runTest {
        fake.ok(Ao3Urls.readings("owner", 1, false), fixture("readings_p1.html"))
        val r = repo()
        val loaded = r.load(LibrarySection.HISTORY, 1)
        assertTrue(loaded is Loaded.Ok)
        val page = (loaded as Loaded.Ok).value
        assertTrue(page.works.isNotEmpty())
        assertEquals(page, r.cached(LibrarySection.HISTORY, 1).first())
        val marks = db.readMarkDao().observeFor(page.works.map { it.workId }).first()
        assertEquals(page.works.map { it.workId }.toSet(), marks.map { it.workId }.toSet())
        assertEquals(42L, marks.first().seenAt)
    }

    @Test fun `later pages are not persisted and marked for later makes no read marks`() = runTest {
        fake.ok(Ao3Urls.readings("owner", 2, false), fixture("readings_p2.html"))
        fake.ok(Ao3Urls.readings("owner", 1, true), fixture("marked_for_later.html"))
        val r = repo()
        assertTrue(r.load(LibrarySection.HISTORY, 2) is Loaded.Ok)
        assertNull(r.cached(LibrarySection.HISTORY, 2).first())
        val ml = r.load(LibrarySection.MARKED_FOR_LATER, 1); assertTrue(ml.toString().take(300), ml is Loaded.Ok)
        val m = (ml as Loaded.Ok).value
        assertNotNull(r.cached(LibrarySection.MARKED_FOR_LATER, 1).first())
        assertTrue(db.readMarkDao().observeFor(m.works.map { it.workId }).first().isEmpty())
    }

    @Test fun `cache is available before the network answers and survives failure`() = runTest {
        fake.ok(Ao3Urls.bookmarks("owner", 1), fixture("bookmarks.html"))
        val r = repo()
        assertNull(r.cached(LibrarySection.BOOKMARKS, 1).first())
        r.load(LibrarySection.BOOKMARKS, 1)
        fake.responses.clear()
        val failed = r.load(LibrarySection.BOOKMARKS, 1)
        assertTrue(failed is Loaded.Error)
        assertNotNull(r.cached(LibrarySection.BOOKMARKS, 1).first())
    }

    @Test fun `corrupt cache row reads as null and is removed`() = runTest {
        db.libraryPageDao().upsert(LibraryPageEntity("owner", "BOOKMARKS", 1, "{nope", 1L))
        val r = repo()
        assertNull(r.cached(LibrarySection.BOOKMARKS, 1).first())
        assertNull(db.libraryPageDao().get("owner", "BOOKMARKS", 1))
    }

    @Test fun `signed out returns NotSignedIn without a request`() = runTest {
        val r = repo(signedIn = false)
        val l = r.load(LibrarySection.HISTORY, 1)
        assertTrue(((l as Loaded.Error).result as FetchResult.Failed).cause is NotSignedInException)
        assertTrue(r.subscriptions(1) is Loaded.Error)
        assertTrue(fake.calls.isEmpty())
    }

    @Test fun `subscriptions load from the network and are not cached`() = runTest {
        fake.ok(Ao3Urls.subscriptions("owner", 1), fixture("subscriptions.html"))
        val r = repo()
        val s = r.subscriptions(1)
        assertTrue(s is Loaded.Ok && (s as Loaded.Ok).value.items.isNotEmpty())
        assertNull(db.libraryPageDao().get("owner", "SUBSCRIPTIONS", 1))
        assertTrue(r.load(LibrarySection.SUBSCRIPTIONS, 1) is Loaded.Error)
    }

    @Test fun `clear empties the cache`() = runTest {
        fake.ok(Ao3Urls.bookmarks("owner", 1), fixture("bookmarks.html"))
        val r = repo()
        r.load(LibrarySection.BOOKMARKS, 1)
        r.clear()
        assertNull(r.cached(LibrarySection.BOOKMARKS, 1).first())
    }

    private val signedOutHtml = """<html><body><header id="header"><div id="login"><a href="/users/login">Log In</a></div></header></body></html>"""
    private val signedInHtml = """<html><body><header id="header"><div id="greeting"><a href="/users/owner">Hi, owner!</a>
        <a href="/users/logout" data-method="delete">Log Out</a></div></header></body></html>"""

    @Test fun `cache is isolated between accounts`() = runTest {
        fake.ok(Ao3Urls.bookmarks("owner", 1), fixture("bookmarks.html"))
        repo(user = "owner").load(LibrarySection.BOOKMARKS, 1)
        val other = repo(user = "Other")
        assertNull(other.cached(LibrarySection.BOOKMARKS, 1).first())
        assertNotNull(repo(user = "OWNER").cached(LibrarySection.BOOKMARKS, 1).first())
    }

    @Test fun `a lapsed session keeps the cache and still requests`() = runTest {
        fake.ok(Ao3Urls.bookmarks("owner", 1), fixture("bookmarks.html"))
        val (session, r) = session()
        r.load(LibrarySection.BOOKMARKS, 1)
        session.observe(signedOutHtml)
        // Stored credentials can renew the session: the account still acts, so nothing is hidden.
        assertNotNull(r.cached(LibrarySection.BOOKMARKS, 1).first())
        fake.calls.clear()
        // Not short-circuited as signed out: the request goes through Session (re-login, then the page).
        val again = r.load(LibrarySection.BOOKMARKS, 1)
        assertTrue(again.toString().take(200), again is Loaded.Ok)
        assertTrue(Ao3Urls.bookmarks("owner", 1).toString() in fake.calls)
    }

    @Test fun `a signed-out page is neither cached nor returned as a library`() = runTest {
        fake.ok(Ao3Urls.bookmarks("owner", 1), signedOutHtml)
        fake.ok(Ao3Urls.subscriptions("owner", 1), signedOutHtml)
        val r = repo()
        val l = r.load(LibrarySection.BOOKMARKS, 1)
        assertTrue(((l as Loaded.Error).result as FetchResult.Failed).cause is NotSignedInException)
        assertNull(db.libraryPageDao().get("owner", "BOOKMARKS", 1))
        val s = r.subscriptions(1)
        assertTrue(((s as Loaded.Error).result as FetchResult.Failed).cause is NotSignedInException)
    }

    @Test fun `a page signed in as another user is neither cached nor returned`() = runTest {
        val asOther = fixture("readings_p1.html").replace("href=\"/users/owner\"", "href=\"/users/Other\"")
        fake.ok(Ao3Urls.readings("owner", 1, false), asOther)
        fake.ok(Ao3Urls.subscriptions("owner", 1), fixture("subscriptions.html").replace("href=\"/users/owner\"", "href=\"/users/Other\""))
        val r = repo()
        val l = r.load(LibrarySection.HISTORY, 1)
        assertTrue(l.toString().take(200), ((l as Loaded.Error).result as FetchResult.Failed).cause is NotSignedInException)
        assertNull(db.libraryPageDao().get("owner", "HISTORY", 1))
        assertNull(db.libraryPageDao().get("other", "HISTORY", 1))
        val ids = io.github.joshitup.lantern.ao3.parse.LibraryParser.readings(asOther, 1).works.map { it.workId }
        assertTrue(ids.isNotEmpty())
        assertTrue(db.readMarkDao().observeFor(ids).first().isEmpty())
        // A fresh session: the page above made this one act as Other.
        val s = repo().subscriptions(1)
        assertTrue(((s as Loaded.Error).result as FetchResult.Failed).cause is NotSignedInException)
    }

    @Test fun `the user check ignores case`() = runTest {
        fake.ok(Ao3Urls.bookmarks("OWNER", 1), fixture("bookmarks.html"))
        val r = repo(user = "OWNER")
        assertTrue(r.load(LibrarySection.BOOKMARKS, 1) is Loaded.Ok)
        assertNotNull(db.libraryPageDao().get("owner", "BOOKMARKS", 1))
    }

    @Test fun `explicit sign-out clears the cache`() = runTest {
        fake.ok(Ao3Urls.bookmarks("owner", 1), fixture("bookmarks.html"))
        val (session, r) = session()
        r.load(LibrarySection.BOOKMARKS, 1)
        session.asAccountSource { r.clear() }.signOut()
        assertNull(db.libraryPageDao().get("owner", "BOOKMARKS", 1))
    }
}
