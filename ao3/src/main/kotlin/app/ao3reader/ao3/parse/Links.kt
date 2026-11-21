package app.ao3reader.ao3.parse

import app.ao3reader.ao3.model.AuthorLink
import app.ao3reader.ao3.model.SeriesLink
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Element

/** Author and series anchors on AO3 pages; anything that is not a plain AO3 link of that shape is dropped. */
internal object Links {
    private val base: HttpUrl = Ao3Urls.BASE_URI.toHttpUrl()

    private fun resolve(a: Element): HttpUrl? {
        val href = a.attr("href").trim()
        if (href.isEmpty()) return null
        val url = base.resolve(href) ?: return null
        return url.takeIf { it.scheme == "https" && it.host == Ao3Urls.HOST && it.port == 443 }
    }

    /** `/users/{user}/pseuds/{pseud}`; null for orphan_account and anything else. */
    fun author(a: Element): AuthorLink? {
        val seg = resolve(a)?.pathSegments ?: return null
        if (seg.size != 4 || seg[0] != "users" || seg[2] != "pseuds") return null
        val user = seg[1]
        val pseud = seg[3]
        if (user.isBlank() || pseud.isBlank() || Ao3Urls.isOrphan(user)) return null
        val name = a.text().trim().ifEmpty { pseud }
        return AuthorLink(name, user, pseud)
    }

    /** `/series/{id}`. */
    fun series(a: Element, part: Int?): SeriesLink? {
        val seg = resolve(a)?.pathSegments ?: return null
        if (seg.size != 2 || seg[0] != "series") return null
        val id = seg[1].takeIf { s -> s.isNotEmpty() && s.all { it in '0'..'9' } }?.toLongOrNull() ?: return null
        return SeriesLink(id, a.text().trim(), part)
    }
}
