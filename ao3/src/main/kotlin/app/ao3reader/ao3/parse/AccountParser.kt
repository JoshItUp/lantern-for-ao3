package app.ao3reader.ao3.parse

import okhttp3.HttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

data class AccountState(val signedIn: Boolean, val username: String?)

object AccountParser {
    private val userHref = Regex("^(?:https?://[^/]+)?/users/([^/?#]+)/?(?:[?#].*)?$")
    private val notNames = setOf("login", "logout")

    /** Signed in = a `/users/<name>` link directly inside `#greeting`; deeper paths (e.g. /users/x/pseuds/y) are skipped. */
    fun state(html: String): AccountState = state(Jsoup.parse(html))

    internal fun state(doc: org.jsoup.nodes.Document): AccountState {
        val greeting = doc.selectFirst("#greeting")
        if (greeting != null) {
            for (a in greeting.select("a[href]")) {
                val name = userHref.find(a.attr("href"))?.groupValues?.get(1) ?: continue
                if (name in notNames) continue
                val decoded = try {
                    java.net.URLDecoder.decode(name.replace("+", "%2B"), "UTF-8")
                } catch (e: IllegalArgumentException) {
                    name
                }
                return AccountState(true, decoded)
            }
        }
        return AccountState(false, null)
    }

    /**
     * What a page says about the session, with evidence either way: signed in (as [state]), signed out
     * only when `#header` holds a login link or login form, and null when it shows neither (JSON,
     * fragments, error pages without the site header).
     */
    fun observed(html: String): AccountState? {
        val doc = Jsoup.parse(html)
        val signedIn = state(doc)
        if (signedIn.signedIn) return signedIn
        val header = doc.selectFirst("#header") ?: return null
        val loginLink = header.select("a[href]").any { a: Element -> isLoginPath(a.attr("href")) }
        val loginForm = header.select("form[action]").any { f: Element -> isLoginPath(f.attr("action")) }
        return if (loginLink || loginForm) AccountState(false, null) else null
    }

    private fun isLoginPath(href: String): Boolean =
        href.substringBefore('#').substringBefore('?').trimEnd('/').let {
            it == "/users/login" || it.endsWith("://archiveofourown.org/users/login")
        }

    fun loginForm(html: String, baseUrl: HttpUrl): Ao3Form? {
        val doc = Jsoup.parse(html)
        val forms = doc.select("form").filter { f: Element ->
            f.attr("action").substringBefore('?').trimEnd('/').endsWith("/users/login") &&
                f.selectFirst("input[name=user[login]]") != null
        }
        val form = forms.firstOrNull { f: Element -> f.id() == "new_user" } ?: forms.firstOrNull() ?: return null
        return FormParser.parse(form, baseUrl)
    }

    fun flashError(html: String): String? {
        val doc = Jsoup.parse(html)
        val el = doc.selectFirst(".flash.error, #error") ?: return null
        return el.text().trim().ifEmpty { null }
    }
}
