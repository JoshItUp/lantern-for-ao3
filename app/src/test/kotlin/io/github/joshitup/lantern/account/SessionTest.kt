package io.github.joshitup.lantern.account

import android.content.Context
import io.github.joshitup.lantern.ao3.net.FetchResult
import io.github.joshitup.lantern.ao3.net.Fetcher
import io.github.joshitup.lantern.ao3.parse.AccountState
import io.github.joshitup.lantern.ao3.parse.Ao3Form
import io.github.joshitup.lantern.data.FakeFetcher
import io.github.joshitup.lantern.data.NotSignedInException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SessionTest {
    private val base = "https://archiveofourown.org"
    private val loginUrl = "$base/users/login"
    private val logoutUrl = "$base/users/logout"
    private val page = "$base/users/Me/readings".toHttpUrl()

    private val prefs = RuntimeEnvironment.getApplication()
        .getSharedPreferences("account-session-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
    private val store = CredentialStore(prefs, FakeSecretBox())
    private val fake = FakeFetcher()
    private var cookiesCleared = 0
    private var now = 1_000_000L

    private fun TestScope.session(st: CredentialStore = store) =
        Session(fake, st, { cookiesCleared++ }, backgroundScope, clock = { now })

    private fun signedIn(name: String = "Me") =
        """<html><body><header id="header"><div id="greeting"><a href="/users/$name">Hi, $name!</a>
        <a href="/users/logout" data-method="delete">Log Out</a></div></header><div id="main">content</div></body></html>"""

    private val signedOut =
        """<html><body><header id="header"><div id="login"><a href="/users/login">Log In</a></div></header><div id="main">public</div></body></html>"""

    private fun loginPage(token: String = "tokA", flash: String? = null) = """<html><body>
        <header id="header"><div id="login"><a href="/users/login">Log In</a>
        <form id="small_login" action="/users/login" method="post"><input type="hidden" name="authenticity_token" value="small"/>
        <input name="user[login]"/><input type="password" name="user[password]"/></form></div></header>
        <div id="main">${if (flash != null) """<div class="flash error">$flash</div>""" else ""}
        <form id="new_user" action="/users/login" method="post">
        <input type="hidden" name="authenticity_token" value="$token"/>
        <input type="text" name="user[login]"/><input type="password" name="user[password]"/>
        <input name="user[remember_me]" type="hidden" value="0"/><input type="checkbox" value="1" name="user[remember_me]"/>
        <input type="submit" name="commit" value="Log in"/></form></div></body></html>"""

    private fun ok(html: String, url: String = "$base/") = FetchResult.Ok(html, url.toHttpUrl())

    // ---- sign in ----

    @Test fun `signIn posts the login form with token and remember_me and succeeds on greeting`() = runTest {
        fake.ok(loginUrl.toHttpUrl(), loginPage())
        fake.postResponse = { _, _ -> ok(signedIn()) }
        val s = session()
        assertFalse(s.state.value.signedIn)

        assertSame(SignInResult.Ok, s.signIn("Me", "pw secret"))

        val (url, fields, _) = fake.posts.single()
        assertEquals(loginUrl, url)
        assertEquals("tokA", fields.single { it.first == "authenticity_token" }.second)
        assertEquals("Me", fields.single { it.first == "user[login]" }.second)
        assertEquals("pw secret", fields.single { it.first == "user[password]" }.second)
        // The hidden "0" comes first so Rails takes the later "1".
        assertEquals(listOf("0", "1"), fields.filter { it.first == "user[remember_me]" }.map { it.second })
        assertEquals(AccountState(true, "Me"), s.state.value)
        assertFalse(s.needsSignIn.value)
        assertEquals("Me" to "pw secret", store.load())
        // The login GET was not anonymous.
        assertEquals(listOf(false), fake.anonymouses)
    }

    @Test fun `signIn rejected on flash error`() = runTest {
        fake.ok(loginUrl.toHttpUrl(), loginPage())
        fake.postResponse = { _, _ -> ok(loginPage("tokB", "The password or user name you entered doesn't match our records."), loginUrl) }
        val s = session()
        val r = s.signIn("Me", "wrong")
        assertTrue(r is SignInResult.Rejected)
        assertEquals("The password or user name you entered doesn't match our records.", (r as SignInResult.Rejected).message)
        assertFalse(r.message.contains("wrong"))
        assertNull(store.load())
        assertFalse(s.state.value.signedIn)
    }

    @Test fun `signIn rejected when a login form is still shown`() = runTest {
        fake.ok(loginUrl.toHttpUrl(), loginPage())
        fake.postResponse = { _, _ -> ok(loginPage("tokB"), loginUrl) }
        val r = session().signIn("Me", "wrong")
        assertTrue(r is SignInResult.Rejected)
        assertNull(store.load())
    }

    @Test fun `signIn error when the login page fails`() = runTest {
        fake.responses[loginUrl] = FetchResult.Failed(null)
        val r = session().signIn("Me", "pw")
        assertEquals(SignInResult.Error(FetchResult.Failed(null)), r)
        assertTrue(fake.posts.isEmpty())
    }

    @Test fun `signIn re-GETs the form once after a blocked POST`() = runTest {
        var gets = 0
        fake.respond = { url, _ -> if (url == loginUrl) ok(loginPage("tok${++gets}"), loginUrl) else null }
        var posts = 0
        fake.postResponse = { _, _ -> if (++posts == 1) FetchResult.Blocked else ok(signedIn()) }
        assertSame(SignInResult.Ok, session().signIn("Me", "pw"))
        assertEquals(listOf("tok1", "tok2"), fake.posts.map { p -> p.second.single { it.first == "authenticity_token" }.second })
    }

    @Test fun `signIn gives up after a second blocked POST`() = runTest {
        fake.ok(loginUrl.toHttpUrl(), loginPage())
        fake.postResponse = { _, _ -> FetchResult.Blocked }
        assertEquals(SignInResult.Error(FetchResult.Blocked), session().signIn("Me", "pw"))
        assertEquals(2, fake.posts.size)
    }

    // ---- state ----

    @Test fun `state starts from stored credentials and observe corrects it`() = runTest {
        store.save("Me", "pw")
        val s = session()
        assertEquals(AccountState(true, "Me"), s.state.value)
        s.observe("""{"json": true}""") // not an AO3 page: ignored
        assertTrue(s.state.value.signedIn)
        s.observe(signedOut)
        assertEquals(AccountState(false, null), s.state.value)
        s.observe(signedIn("Other"))
        assertEquals(AccountState(true, "Other"), s.state.value)
    }

    // ---- silent re-login ----

    @Test fun `expired session re-logins silently and retries once`() = runTest {
        store.save("Me", "pw")
        fake.ok(loginUrl.toHttpUrl(), loginPage())
        var loggedIn = false
        fake.respond = { url, _ -> if (url == page.toString()) ok(if (loggedIn) signedIn() else signedOut, url) else null }
        fake.postResponse = { _, _ -> loggedIn = true; ok(signedIn()) }
        val s = session()

        val r = s.authedGet(page)

        assertTrue((r as FetchResult.Ok).html.contains("greeting"))
        assertEquals(2, fake.calls.count { it == page.toString() })
        assertEquals(1, fake.posts.size)
        assertEquals("pw", fake.posts.single().second.single { it.first == "user[password]" }.second)
        assertEquals(AccountState(true, "Me"), s.state.value)
    }

    @Test fun `relogin once then gives up`() = runTest {
        // The stored password is wrong now: one failed login, then the sign-in screen.
        store.save("Me", "old")
        fake.ok(loginUrl.toHttpUrl(), loginPage())
        fake.ok(page, signedOut)
        fake.postResponse = { _, _ -> ok(loginPage("tokB", "The password or user name you entered doesn't match our records."), loginUrl) }
        val s = session()

        val r = s.authedGet(page)

        assertTrue(r is FetchResult.Ok)
        assertEquals(1, fake.posts.size)
        assertEquals(1, fake.calls.count { it == page.toString() })
        assertFalse(s.state.value.signedIn)
        assertTrue(s.needsSignIn.value)
        // Credentials are kept; nothing is retried on later calls.
        assertEquals("Me" to "old", store.load())
        s.authedGet(page)
        s.authedPost(Ao3Form("$base/works/1/kudos".toHttpUrl(), "post", listOf("authenticity_token" to "t")), emptyList())
        assertEquals(1, fake.posts.count { it.first == loginUrl })
        // A successful manual sign-in clears the prompt.
        fake.postResponse = { _, _ -> ok(signedIn()) }
        assertSame(SignInResult.Ok, s.signIn("Me", "new"))
        assertFalse(s.needsSignIn.value)
        assertEquals("Me" to "new", store.load())
    }

    // ---- acting account ----

    @Test fun `acting account survives a lapse and goes with needsSignIn or sign-out`() = runTest {
        store.save("me@mail", "old")
        store.saveDisplayName("Me")
        val s = session()
        assertEquals("Me", s.actingAccount.value)
        // A lapse: signed-out evidence, but stored credentials can renew it silently.
        s.observe(signedOut)
        assertFalse(s.state.value.signedIn)
        assertEquals("Me", s.actingAccount.value)
        // The stored password is rejected: nothing to act as any more.
        fake.ok(loginUrl.toHttpUrl(), loginPage())
        fake.ok(page, signedOut)
        fake.postResponse = { _, _ -> ok(loginPage("tokB", "The password or user name you entered doesn't match our records."), loginUrl) }
        s.authedGet(page)
        assertTrue(s.needsSignIn.value)
        assertNull(s.actingAccount.value)
        // Signing in again restores it; signing out clears it.
        fake.postResponse = { _, _ -> ok(signedIn()) }
        assertSame(SignInResult.Ok, s.signIn("me@mail", "new"))
        assertEquals("Me", s.actingAccount.value)
        fake.ok(logoutUrl.toHttpUrl(), signedOut)
        s.signOut()
        assertNull(s.actingAccount.value)
    }

    @Test fun `acting account flow emits changes`() = runTest {
        store.save("Me", "pw")
        val s = session()
        val seen = mutableListOf<String?>()
        val job = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { s.actingAccount.collect { seen += it } }
        // merge() subscribes on the next tick; let it see "Me" before signOut() suspends on the IO thread.
        runCurrent()
        assertEquals(listOf<String?>("Me"), seen)
        fake.ok(logoutUrl.toHttpUrl(), signedOut)
        s.signOut()
        runCurrent()
        assertEquals(listOf("Me", null), seen)
        job.cancel()
    }

    @Test fun `still signed out after a good re-login does not loop`() = runTest {
        store.save("Me", "pw")
        fake.ok(loginUrl.toHttpUrl(), loginPage())
        fake.ok(page, signedOut)
        fake.postResponse = { _, _ -> ok(signedIn()) }
        val s = session()
        val r = s.authedGet(page)
        assertTrue(r is FetchResult.Ok)
        assertEquals(2, fake.calls.count { it == page.toString() })
        assertEquals(1, fake.posts.size)
        assertFalse(s.state.value.signedIn)
    }

    @Test fun `no stored credentials means no re-login`() = runTest {
        fake.ok(page, signedOut)
        val s = session()
        assertTrue(s.authedGet(page) is FetchResult.Ok)
        assertTrue(fake.posts.isEmpty())
        assertEquals(1, fake.calls.size)
    }

    @Test fun `authedGet passes forceNetwork through, false by default`() = runTest {
        store.save("Me", "pw")
        fake.ok(page, signedIn())
        val s = session()
        s.authedGet(page)
        s.authedGet(page, forceNetwork = true)
        assertEquals(listOf(false, true), fake.forceNetworks)
    }

    @Test fun `a non-html or failed response never triggers re-login`() = runTest {
        store.save("Me", "pw")
        fake.ok(page, """{"a":1}""")
        val s = session()
        s.authedGet(page)
        fake.responses[page.toString()] = FetchResult.Failed(null)
        assertEquals(FetchResult.Failed(null), s.authedGet(page))
        assertTrue(fake.posts.isEmpty())
        assertTrue(s.state.value.signedIn)
        assertEquals(listOf(null, null), fake.accepts)
    }

    @Test fun `concurrent callers share one re-login`() = runTest {
        store.save("Me", "pw")
        fake.ok(loginUrl.toHttpUrl(), loginPage())
        var loggedIn = false
        val gate = CompletableDeferred<Unit>()
        fake.gate = gate
        fake.respond = { url, _ -> if (url == page.toString()) ok(if (loggedIn) signedIn() else signedOut, url) else null }
        fake.postResponse = { _, _ -> loggedIn = true; ok(signedIn()) }
        val s = session()

        val a = async { s.authedGet(page) }
        val b = async { s.authedGet(page) }
        runCurrent()
        gate.complete(Unit)
        val ra = a.await() as FetchResult.Ok
        val rb = b.await() as FetchResult.Ok

        assertEquals(1, fake.posts.size)
        assertTrue(ra.html.contains("greeting") && rb.html.contains("greeting"))
    }

    @Test fun `observed sign-out re-logins before the next authed call`() = runTest {
        store.save("Me", "pw")
        fake.ok(loginUrl.toHttpUrl(), loginPage())
        var loggedIn = false
        fake.respond = { url, _ -> if (url == page.toString()) ok(if (loggedIn) signedIn() else signedOut, url) else null }
        fake.postResponse = { _, _ -> loggedIn = true; ok(signedIn()) }
        val s = session()
        s.observe(signedOut)
        assertTrue((s.authedGet(page) as FetchResult.Ok).html.contains("greeting"))
        assertEquals(1, fake.calls.count { it == page.toString() })
        assertEquals(1, fake.posts.size)
    }

    // ---- authed POST ----

    @Test fun `authedPost applies overrides`() = runTest {
        store.save("Me", "pw")
        val action = "$base/works/1/bookmarks"
        fake.postResponse = { url, _ -> ok(signedIn(), url) }
        val form = Ao3Form(action.toHttpUrl(), "post", listOf(
            "authenticity_token" to "t", "_method" to "put", "bookmark[notes]" to "old", "bookmark[private]" to "0",
        ))
        val r = session().authedPost(form, listOf("bookmark[notes]" to "new", "bookmark[rec]" to "1"))
        assertTrue(r is FetchResult.Ok)
        assertEquals(
            listOf("authenticity_token" to "t", "_method" to "put", "bookmark[notes]" to "new", "bookmark[private]" to "0", "bookmark[rec]" to "1"),
            fake.posts.single().second,
        )
    }

    @Test fun `authedPost never resends after a re-login`() = runTest {
        store.save("Me", "pw")
        fake.ok(loginUrl.toHttpUrl(), loginPage())
        val action = "$base/works/1/kudos"
        fake.postResponse = { url, _ -> if (url == loginUrl) ok(signedIn()) else ok(signedOut, url) }
        val s = session()

        val r = s.authedPost(Ao3Form(action.toHttpUrl(), "post", listOf("authenticity_token" to "t")), emptyList())

        assertTrue(r is FetchResult.Failed && r.cause is SessionRenewedException)
        assertEquals(1, fake.posts.count { it.first == action })
        assertEquals(1, fake.posts.count { it.first == loginUrl })
        assertEquals(AccountState(true, "Me"), s.state.value)
    }

    @Test fun `authedPost re-logins before sending when the state is already signed out`() = runTest {
        store.save("Me", "pw")
        fake.ok(loginUrl.toHttpUrl(), loginPage())
        val action = "$base/works/1/kudos"
        fake.postResponse = { url, _ -> ok(signedIn(), url) }
        val s = session()
        s.observe(signedOut)
        assertTrue(s.authedPost(Ao3Form(action.toHttpUrl(), "post", emptyList()), emptyList()) is FetchResult.Ok)
        assertEquals(listOf(loginUrl, action), fake.posts.map { it.first })
    }

    @Test fun `authedPost sends nothing when known signed out and re-login is paused`() = runTest {
        store.save("Me", "pw")
        fake.responses[loginUrl] = FetchResult.RateLimited(60)
        fake.ok(page, signedOut)
        val s = session()
        s.authedGet(page) // the re-login fails: paused, state signed out
        assertFalse(s.state.value.signedIn)
        val action = "$base/works/1/kudos"
        fake.postResponse = { url, _ -> ok(signedIn(), url) }

        val r = s.authedPost(Ao3Form(action.toHttpUrl(), "post", listOf("authenticity_token" to "t")), emptyList())

        assertTrue(r is FetchResult.Failed && r.cause is NotSignedInException)
        assertTrue(fake.posts.isEmpty())
        assertEquals(1, fake.calls.count { it == loginUrl })
    }

    @Test fun `authedPost sends nothing when known signed out and the pre-login fails`() = runTest {
        store.save("Me", "pw")
        fake.responses[loginUrl] = FetchResult.RateLimited(60)
        val s = session()
        s.observe(signedOut)
        val action = "$base/works/1/kudos"
        fake.postResponse = { url, _ -> ok(signedIn(), url) }

        val r = s.authedPost(Ao3Form(action.toHttpUrl(), "post", listOf("authenticity_token" to "t")), emptyList())

        assertTrue(r is FetchResult.Failed && r.cause is NotSignedInException)
        assertTrue(fake.posts.isEmpty())
        assertEquals(1, fake.calls.count { it == loginUrl })
    }

    @Test fun `authedPost sends nothing when known signed out without stored credentials`() = runTest {
        val s = session()
        s.observe(signedOut)
        val action = "$base/works/1/kudos"
        fake.postResponse = { url, _ -> ok(signedIn(), url) }
        val r = s.authedPost(Ao3Form(action.toHttpUrl(), "post", emptyList()), emptyList())
        assertTrue(r is FetchResult.Failed && r.cause is NotSignedInException)
        assertTrue(fake.posts.isEmpty())
    }

    // ---- races (I2) ----

    @Test fun `signOut cancels an in-flight re-login and nothing revives the session`() = runTest {
        store.save("Me", "pw")
        fake.ok(loginUrl.toHttpUrl(), loginPage())
        fake.ok(page, signedOut)
        fake.responses[logoutUrl] = FetchResult.Failed(null)
        val gate = CompletableDeferred<Unit>()
        fake.gates[loginUrl] = gate
        fake.postResponse = { _, _ -> ok(signedIn()) }
        val s = session()

        val call = async { s.authedGet(page) }
        runCurrent() // the re-login is waiting on the login page
        s.signOut()
        gate.complete(Unit)
        val r = call.await()

        assertTrue((r as FetchResult.Ok).html.contains("id=\"login\""))
        assertTrue(fake.posts.none { it.first == loginUrl })
        assertNull(store.load())
        assertEquals(AccountState(false, null), s.state.value)
        assertEquals(1, cookiesCleared)
        // A signed-in page fetched elsewhere after sign-out doesn't flip the state back.
        s.observe(signedIn())
        assertFalse(s.state.value.signedIn)
    }

    @Test fun `signIn supersedes an old re-login`() = runTest {
        store.save("Me", "old")
        fake.ok(loginUrl.toHttpUrl(), loginPage())
        fake.ok(page, signedOut)
        val gate = CompletableDeferred<Unit>()
        fake.gates[loginUrl] = gate
        fake.postResponse = { _, fields ->
            if (fields.single { it.first == "user[password]" }.second == "new") ok(signedIn())
            else ok(loginPage("tokB", "The password or user name you entered doesn't match our records."), loginUrl)
        }
        val s = session()

        val old = async { s.authedGet(page) }
        runCurrent()
        val manual = async { s.signIn("Me", "new") }
        runCurrent()
        gate.complete(Unit)

        assertSame(SignInResult.Ok, manual.await())
        assertTrue(old.await() is FetchResult.Ok)
        assertEquals(listOf("new"), fake.posts.map { p -> p.second.single { it.first == "user[password]" }.second })
        assertFalse(s.needsSignIn.value)
        assertEquals(AccountState(true, "Me"), s.state.value)
        assertEquals("Me" to "new", store.load())
    }

    @Test fun `signed-in observations are ignored without an account`() = runTest {
        val s = session()
        s.observe(signedIn())
        assertFalse(s.state.value.signedIn)
    }

    // ---- verification (I3), storms (I4), save failure (I5) ----

    @Test fun `explicit signIn on an already signed-in session still posts the password`() = runTest {
        var gets = 0
        fake.respond = { url, _ -> if (url == loginUrl) ok(if (++gets == 1) signedIn() else loginPage(), loginUrl) else null }
        fake.postResponse = { _, _ -> ok(signedIn()) }
        assertSame(SignInResult.Ok, session().signIn("Me", "pw"))
        assertEquals(1, cookiesCleared)
        assertEquals(2, gets)
        assertEquals("pw", fake.posts.single().second.single { it.first == "user[password]" }.second)
    }

    @Test fun `silent re-login accepts an already signed-in login page`() = runTest {
        store.save("Me", "pw")
        fake.ok(loginUrl.toHttpUrl(), signedIn())
        var n = 0
        fake.respond = { url, _ -> if (url == page.toString()) ok(if (++n == 1) signedOut else signedIn(), url) else null }
        val r = session().authedGet(page)
        assertTrue((r as FetchResult.Ok).html.contains("greeting"))
        assertTrue(fake.posts.isEmpty())
        assertEquals(0, cookiesCleared)
    }

    @Test fun `a failed re-login pauses silent attempts for five minutes`() = runTest {
        store.save("Me", "pw")
        fake.responses[loginUrl] = FetchResult.RateLimited(60)
        fake.ok(page, signedOut)
        val s = session()

        s.authedGet(page)
        s.authedGet(page)
        assertEquals(1, fake.calls.count { it == loginUrl })
        assertFalse(s.needsSignIn.value)

        now += 5 * 60_000L
        s.authedGet(page)
        assertEquals(2, fake.calls.count { it == loginUrl })

        // A signed-in page ends the pause early.
        s.observe(signedIn())
        s.authedGet(page)
        assertEquals(3, fake.calls.count { it == loginUrl })
    }

    @Test fun `credentials that cannot be saved still leave a live session`() = runTest {
        val broken = CredentialStore(prefs, object : SecretBox {
            override fun seal(plain: ByteArray): ByteArray = throw java.security.ProviderException("keystore down")
            override fun open(sealed: ByteArray): ByteArray = throw java.security.ProviderException("keystore down")
        })
        fake.ok(loginUrl.toHttpUrl(), loginPage())
        fake.postResponse = { _, _ -> ok(signedIn()) }
        val s = session(broken)

        assertSame(SignInResult.Ok, s.signIn("Me", "pw"))

        assertFalse(s.credentialsSaved.value)
        assertEquals(AccountState(true, "Me"), s.state.value)
        assertNull(broken.username())
        // The live session still follows the pages the app loads.
        s.observe(signedIn("Me"))
        assertTrue(s.state.value.signedIn)
    }

    // ---- email sign-in (M2), header evidence (M1), background calls (M5) ----

    @Test fun `email sign-in stores the AO3 name for the state and the same-user check`() = runTest {
        fake.ok(loginUrl.toHttpUrl(), loginPage())
        fake.postResponse = { _, _ -> ok(signedIn("Me")) }
        val s = session()
        assertSame(SignInResult.Ok, s.signIn("me@example.com", "pw"))
        assertEquals("Me", store.displayName())
        assertEquals("me@example.com" to "pw", store.load())
        assertTrue(s.credentialsSaved.value)

        // A fresh process starts with the AO3 name, and a silent re-login recognises it.
        val s2 = session()
        assertEquals(AccountState(true, "Me"), s2.state.value)
        fake.posts.clear()
        fake.ok(loginUrl.toHttpUrl(), signedIn("Me"))
        var n = 0
        fake.respond = { url, _ -> if (url == page.toString()) ok(if (++n == 1) signedOut else signedIn(), url) else null }
        assertTrue((s2.authedGet(page) as FetchResult.Ok).html.contains("greeting"))
        assertTrue(fake.posts.isEmpty())
    }

    @Test fun `a header without a login link is not treated as signed out`() = runTest {
        store.save("Me", "pw")
        fake.ok(page, """<html><body><header id="header"><h1>Archive</h1></header><div id="main">x</div></body></html>""")
        val s = session()
        assertTrue(s.authedGet(page) is FetchResult.Ok)
        assertTrue(fake.posts.isEmpty())
        assertTrue(s.state.value.signedIn)
    }

    @Test fun `background authedGet re-logins without the challenge solver`() = runTest {
        store.save("Me", "pw")
        fake.ok(loginUrl.toHttpUrl(), loginPage())
        var loggedIn = false
        fake.respond = { url, _ -> if (url == page.toString()) ok(if (loggedIn) signedIn() else signedOut, url) else null }
        fake.postResponse = { _, _ -> loggedIn = true; ok(signedIn()) }
        assertTrue((session().authedGet(page, interactive = false) as FetchResult.Ok).html.contains("greeting"))
        assertEquals(listOf(false, false, false), fake.interactives)
        assertEquals(listOf(false), fake.postInteractives)
    }

    // ---- round 2: signIn vs signOut, background pauses ----

    @Test fun `signOut during signIn's final step wins and nothing is written`() = runTest {
        store.save("Me", "old")
        fake.ok(page, signedOut)
        fake.responses[logoutUrl] = FetchResult.Failed(null)
        val postGate = CompletableDeferred<Unit>()
        // Holds signIn after AO3 accepted the new password, i.e. after the login itself succeeded.
        val held = object : Fetcher by fake {
            override suspend fun post(url: HttpUrl, fields: List<Pair<String, String>>, accept: String?, interactive: Boolean): FetchResult {
                val r = fake.post(url, fields, accept, interactive)
                if (("user[password]" to "new") in fields) postGate.await()
                return r
            }
        }
        fake.postResponse = { _, _ -> ok(signedIn()) }
        fake.ok(loginUrl.toHttpUrl(), loginPage())
        val s = Session(held, store, { cookiesCleared++ }, backgroundScope, clock = { now })

        val signIn = async { s.signIn("Me", "new") }
        runCurrent() // signIn waits after its POST
        val loginGate = CompletableDeferred<Unit>()
        fake.gates[loginUrl] = loginGate
        val bg = async { s.authedGet(page) }
        runCurrent() // a re-login is in flight, waiting on the login page
        // signOut takes the lock and waits for the re-login to be cancelled; signIn resumes meanwhile.
        val signOut = async(start = CoroutineStart.UNDISPATCHED) { s.signOut() }
        postGate.complete(Unit)
        runCurrent()
        signOut.await()

        val r = signIn.await()
        assertTrue(r is SignInResult.Error && (r.result as FetchResult.Failed).cause is SessionSupersededException)
        assertTrue(bg.await() is FetchResult.Ok)
        assertNull(store.load())
        assertFalse(s.credentialsSaved.value)
        assertEquals(AccountState(false, null), s.state.value)
        s.observe(signedIn())
        assertFalse(s.state.value.signedIn)
    }

    /** A Session whose sign-in POST with [password] waits on the returned gate (after AO3 answered it). */
    private fun TestScope.heldSignIn(password: String): Pair<Session, CompletableDeferred<Unit>> {
        val postGate = CompletableDeferred<Unit>()
        val held = object : Fetcher by fake {
            override suspend fun post(url: HttpUrl, fields: List<Pair<String, String>>, accept: String?, interactive: Boolean): FetchResult {
                val r = fake.post(url, fields, accept, interactive)
                if (("user[password]" to password) in fields) postGate.await()
                return r
            }
        }
        return Session(held, store, { cookiesCleared++ }, backgroundScope, clock = { now }) to postGate
    }

    @Test fun `a signIn whose POST lands after signOut clears the cookies it brought`() = runTest {
        fake.ok(loginUrl.toHttpUrl(), loginPage())
        fake.postResponse = { _, _ -> ok(signedIn()) }
        fake.responses[logoutUrl] = FetchResult.Failed(null)
        val (s, postGate) = heldSignIn("pw")

        val signIn = async { s.signIn("Me", "pw") }
        runCurrent() // AO3 accepted the login; its session cookies are in the jar
        s.signOut()
        assertEquals(1, cookiesCleared)
        postGate.complete(Unit)

        val r = signIn.await()
        assertTrue(r is SignInResult.Error && (r.result as FetchResult.Failed).cause is SessionSupersededException)
        assertEquals(2, cookiesCleared)
        assertEquals(AccountState(false, null), s.state.value)
    }

    @Test fun `a superseded signIn leaves a newer signIn's cookies alone`() = runTest {
        fake.ok(loginUrl.toHttpUrl(), loginPage())
        fake.postResponse = { _, _ -> ok(signedIn()) }
        fake.responses[logoutUrl] = FetchResult.Failed(null)
        val (s, postGate) = heldSignIn("old")

        val first = async { s.signIn("Me", "old") }
        runCurrent()
        s.signOut()
        assertSame(SignInResult.Ok, s.signIn("Me", "new"))
        val cleared = cookiesCleared
        postGate.complete(Unit)

        assertTrue(first.await() is SignInResult.Error)
        assertEquals(cleared, cookiesCleared)
        assertTrue(s.state.value.signedIn)
    }

    @Test fun `a background Blocked pause does not stop interactive re-login`() = runTest {
        store.save("Me", "pw")
        var loggedIn = false
        fake.respond = { url, interactive ->
            when (url) {
                loginUrl -> if (interactive) ok(loginPage(), loginUrl) else FetchResult.Blocked
                page.toString() -> ok(if (loggedIn) signedIn() else signedOut, url)
                else -> null
            }
        }
        fake.postResponse = { _, _ -> loggedIn = true; ok(signedIn()) }
        val s = session()

        s.authedGet(page, interactive = false)
        s.authedGet(page, interactive = false) // paused for background callers
        assertEquals(1, loginGets(interactive = false))

        val r = s.authedGet(page)
        assertTrue((r as FetchResult.Ok).html.contains("greeting"))
        assertEquals(1, loginGets(interactive = true))
        assertEquals(AccountState(true, "Me"), s.state.value)
    }

    @Test fun `an interactive caller that joined a blocked background attempt tries once itself`() = runTest {
        store.save("Me", "pw")
        var loggedIn = false
        val gate = CompletableDeferred<Unit>()
        fake.gates[loginUrl] = gate
        fake.respond = { url, interactive ->
            when (url) {
                loginUrl -> if (interactive) ok(loginPage(), loginUrl) else FetchResult.Blocked
                page.toString() -> ok(if (loggedIn) signedIn() else signedOut, url)
                else -> null
            }
        }
        fake.postResponse = { _, _ -> loggedIn = true; ok(signedIn()) }
        val s = session()

        val bg = async { s.authedGet(page, interactive = false) }
        runCurrent() // background re-login waits on the login page
        val fg = async { s.authedGet(page) }
        runCurrent() // joins it
        gate.complete(Unit)

        assertTrue((fg.await() as FetchResult.Ok).html.contains("greeting"))
        assertTrue(bg.await() is FetchResult.Ok)
        assertEquals(1, loginGets(interactive = false))
        assertEquals(1, loginGets(interactive = true))
        assertEquals(listOf(true), fake.postInteractives)
    }

    @Test fun `an interactive failure pauses everyone`() = runTest {
        store.save("Me", "pw")
        fake.responses[loginUrl] = FetchResult.Blocked
        fake.ok(page, signedOut)
        val s = session()
        s.authedGet(page)
        s.authedGet(page)
        s.authedGet(page, interactive = false)
        assertEquals(1, fake.calls.count { it == loginUrl })
    }

    private fun loginGets(interactive: Boolean) =
        fake.calls.indices.count { fake.calls[it] == loginUrl && fake.interactives[it] == interactive }

    // ---- sign out ----

    @Test fun `signOut posts the logout form, clears cookies and credentials`() = runTest {
        store.save("Me", "pw")
        fake.ok(logoutUrl.toHttpUrl(), """<html><body><header id="header"><div id="greeting"><a href="/users/Me">Hi</a></div></header>
            <form action="/users/logout" method="post"><input type="hidden" name="_method" value="delete"/>
            <input type="hidden" name="authenticity_token" value="tokL"/><input type="submit" value="Yes, log out"/></form></body></html>""")
        fake.postResponse = { _, _ -> ok(signedOut) }
        val s = session()

        s.signOut()

        val (url, fields, _) = fake.posts.single()
        assertEquals(logoutUrl, url)
        assertEquals(listOf("_method" to "delete", "authenticity_token" to "tokL"), fields)
        assertEquals(1, cookiesCleared)
        assertNull(store.load())
        assertEquals(AccountState(false, null), s.state.value)
        assertFalse(s.needsSignIn.value)
    }

    @Test fun `signOut without a logout form uses the page csrf token`() = runTest {
        store.save("Me", "pw")
        fake.ok(logoutUrl.toHttpUrl(), """<html><head><meta name="csrf-token" content="metaTok"/></head><body>${signedIn()}</body></html>""")
        fake.postResponse = { _, _ -> ok(signedOut) }
        session().signOut()
        assertEquals(listOf("_method" to "delete", "authenticity_token" to "metaTok"), fake.posts.single().second)
        assertEquals(1, cookiesCleared)
    }

    @Test fun `signOut still clears locally when the network fails`() = runTest {
        store.save("Me", "pw")
        fake.responses[logoutUrl] = FetchResult.Failed(null)
        val s = session()
        s.signOut()
        assertTrue(fake.posts.isEmpty())
        assertEquals(1, cookiesCleared)
        assertNull(store.load())
        assertFalse(s.state.value.signedIn)
    }
}
