package app.ao3reader.ao3.parse

import okhttp3.HttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

data class AccountState(val signedIn: Boolean, val username: String?)

object AccountParser {
    private val userHref = Regex("^(?:https?://[^/]+)?/users/([^/?#]+)/?(?:[?#].*)?$")
    private val notNames = setOf("login", "logout")

    fun state(html: String): AccountState {
        val doc = Jsoup.parse(html)
        val greeting = doc.selectFirst("#greeting")
        if (greeting != null) {
            for (a in greeting.select("a[href]")) {
                val name = userHref.find(a.attr("href"))?.groupValues?.get(1) ?: continue
                if (name in notNames) continue
                return AccountState(true, java.net.URLDecoder.decode(name.replace("+", "%2B"), "UTF-8"))
            }
        }
        return AccountState(false, null)
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
