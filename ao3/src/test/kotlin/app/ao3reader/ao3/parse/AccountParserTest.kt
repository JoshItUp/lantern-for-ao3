package app.ao3reader.ao3.parse

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class AccountParserTest {
    private fun res(n: String) = javaClass.getResource("/fixtures/$n")!!.readText()
    private val base = "https://archiveofourown.org/users/login".toHttpUrl()

    @Test fun `signed out real page`() {
        assertEquals(AccountState(false, null), AccountParser.state(res("chapter_signed_out.html")))
    }

    private val realSignedIn = listOf(
        "chapter_plain.html", "chapter_bookmarked.html", "readings_p1.html", "readings_p2.html",
        "marked_for_later.html", "bookmarks.html", "subscriptions.html",
    ).map { "real_signed_in/$it" }

    @Test fun `signed in page`() {
        assertEquals(AccountState(true, "owner"), AccountParser.state(res("real_signed_in/chapter_plain.html")))
    }

    @Test fun `every real signed-in page is signed in as owner, observed, with no login form or flash error`() {
        for (n in realSignedIn) {
            val html = res(n)
            assertEquals(n, AccountState(true, "owner"), AccountParser.state(html))
            assertEquals(n, AccountState(true, "owner"), AccountParser.observed(html))
            assertNull(n, AccountParser.loginForm(html, base))
            assertNull(n, AccountParser.flashError(html))
        }
    }

    @Test fun `real signed-in pages log out by a data-method link, not a form, with a csrf meta token`() {
        // Session.signOut falls back to POST /users/logout with _method=delete and the meta csrf-token.
        for (n in realSignedIn) {
            val doc = org.jsoup.Jsoup.parse(res(n))
            assertNull(n, FormParser.find(res(n), base, "form[action*=/users/logout]"))
            val link = doc.selectFirst("#greeting a[href^=/users/logout]")
            assertNotNull(n, link)
            assertEquals(n, "delete", link!!.attr("data-method"))
            assertEquals(n, "x", doc.selectFirst("meta[name=csrf-token]")!!.attr("content"))
        }
    }

    @Test fun `login page is signed out and has form`() {
        val html = res("login_page.html")
        assertFalse(AccountParser.state(html).signedIn)
        val f = AccountParser.loginForm(html, base)!!
        assertEquals("https://archiveofourown.org/users/login".toHttpUrl(), f.action)
        assertEquals("post", f.method)
        val names = f.fields.map { it.first }
        assertTrue("user[login]" in names)
        assertTrue("authenticity_token" in names)
        assertEquals("tok123", f.fields.first { it.first == "authenticity_token" }.second)
        assertEquals("0", f.fields.first { it.first == "user[remember_me]" }.second)
    }

    @Test fun `login form findable on signed-out real page`() {
        assertNotNull(AccountParser.loginForm(res("chapter_signed_out.html"), base))
    }

    @Test fun `flash error text`() {
        assertEquals("The password or user name you entered doesn't match our records. Please try again.", AccountParser.flashError(res("login_page.html")))
        assertNull(AccountParser.flashError(res("real_signed_in/chapter_plain.html")))
    }

    private fun greet(href: String) = """<div id="greeting"><a href="$href">Hi</a></div>"""

    @Test fun `malformed percent name does not throw`() {
        assertEquals(AccountState(true, "bad%zz"), AccountParser.state(greet("/users/bad%zz")))
    }

    @Test fun `encoded name decodes`() {
        assertEquals(AccountState(true, "a b"), AccountParser.state(greet("/users/a%20b")))
    }

    @Test fun `byline link outside greeting stays signed out`() {
        assertEquals(AccountState(false, null), AccountParser.state("""<div id="login"><a href="/users/login">Log In</a></div><a rel="author" href="/users/x">x</a>"""))
    }

    @Test fun `deeper greeting path skipped`() {
        assertEquals(AccountState(false, null), AccountParser.state(greet("/users/x/pseuds/y")))
    }

    // observed(): signed in, signed out only with positive evidence (a login link/form in the header), else unknown.

    @Test fun `observed signed in and real signed out`() {
        assertEquals(AccountState(true, "owner"), AccountParser.observed(res("real_signed_in/chapter_plain.html")))
        assertEquals(AccountState(false, null), AccountParser.observed(res("chapter_signed_out.html")))
        assertEquals(AccountState(false, null), AccountParser.observed(res("login_page.html")))
    }

    @Test fun `observed is unknown without a header login link`() {
        assertNull(AccountParser.observed("""{"json": true}"""))
        assertNull(AccountParser.observed("""<html><body><header id="header"><h1>AO3</h1></header></body></html>"""))
        // A login link outside the header (e.g. in work text) is not evidence.
        assertNull(AccountParser.observed("""<header id="header"></header><div id="main"><a href="/users/login">log in</a></div>"""))
    }

    @Test fun `observed signed out by header login form or link`() {
        assertEquals(AccountState(false, null), AccountParser.observed(
            """<header id="header"><form action="/users/login" method="post"><input name="user[login]"/></form></header>"""))
        assertEquals(AccountState(false, null), AccountParser.observed(
            """<header id="header"><a href="https://archiveofourown.org/users/login?return_to=%2F">Log In</a></header>"""))
    }
}
