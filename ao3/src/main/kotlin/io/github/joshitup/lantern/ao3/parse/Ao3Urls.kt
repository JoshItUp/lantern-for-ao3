package io.github.joshitup.lantern.ao3.parse

import io.github.joshitup.lantern.ao3.model.AuthorLink
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object Ao3Urls {
    const val HOST = "archiveofourown.org"
    private const val BASE = "https://$HOST"
    private const val ORPHAN = "orphan_account"
    /** Base URI for parsing AO3 HTML, so relative links resolve (`Element.absUrl`). */
    const val BASE_URI = "$BASE/"

    /** chapterId null -> /works/{id}. Always carries view_adult=true. */
    fun chapter(workId: Long, chapterId: Long?): HttpUrl {
        val path = if (chapterId == null) "$BASE/works/$workId" else "$BASE/works/$workId/chapters/$chapterId"
        return path.toHttpUrl().newBuilder().addQueryParameter("view_adult", "true").build()
    }

    /** Every posted chapter of the work on one page. */
    fun fullWork(workId: Long): HttpUrl = "$BASE/works/$workId?view_full_work=true".toHttpUrl()

    fun login(): HttpUrl = "$BASE/users/login".toHttpUrl()

    /** GET shows a confirmation form; the form (or a DELETE override with the page's CSRF token) logs out. */
    fun logout(): HttpUrl = "$BASE/users/logout".toHttpUrl()

    private fun userUrl(user: String, tail: String): HttpUrl =
        "$BASE/".toHttpUrl().newBuilder().addPathSegment("users").addPathSegment(user).addPathSegment(tail).build()

    private fun paged(b: HttpUrl.Builder, page: Int): HttpUrl {
        if (page > 1) b.addQueryParameter("page", page.toString())
        return b.build()
    }

    /** History, or Marked for Later with toRead. Page 1 carries no page parameter. */
    fun readings(user: String, page: Int, toRead: Boolean): HttpUrl {
        val b = userUrl(user, "readings").newBuilder()
        if (toRead) b.addQueryParameter("show", "to-read")
        return paged(b, page)
    }

    fun bookmarks(user: String, page: Int): HttpUrl = paged(userUrl(user, "bookmarks").newBuilder(), page)

    fun subscriptions(user: String, page: Int): HttpUrl = paged(userUrl(user, "subscriptions").newBuilder(), page)

    fun navigate(workId: Long): HttpUrl = "$BASE/works/$workId/navigate".toHttpUrl()

    /** kind: fandom | character | relationship | freeform */
    fun autocomplete(kind: String, term: String): HttpUrl =
        "$BASE/autocomplete/$kind".toHttpUrl().newBuilder().addQueryParameter("term", term).build()

    /** AO3's placeholder owner of orphaned works: never a real author to list. */
    fun isOrphan(user: String): Boolean = user.equals(ORPHAN, ignoreCase = true)

    fun series(id: Long): HttpUrl = "$BASE/series/$id".toHttpUrl()

    /** The author's works: the user's whole list when the pseud is the default one, else that pseud's. */
    fun authorWorks(a: AuthorLink): HttpUrl {
        val b = "$BASE/".toHttpUrl().newBuilder().addPathSegment("users").addPathSegment(a.user)
        if (!a.pseud.equals(a.user, ignoreCase = true)) b.addPathSegment("pseuds").addPathSegment(a.pseud)
        return b.addPathSegment("works").build()
    }

    /** [url] at [page]: any page parameter is replaced; page 1 carries none. */
    fun page(url: HttpUrl, page: Int): HttpUrl = paged(url.newBuilder().removeAllQueryParameters("page"), page)

    /**
     * The in-app work list for [url], normalized: `/series/{id}`, or an author's works
     * (`/users/{u}[/works]`, `/users/{u}/pseuds/{p}[/works]`). Null for anything else: other hosts,
     * schemes, ports, credentials, other paths, orphan_account.
     */
    fun workList(url: HttpUrl): HttpUrl? {
        if (url.scheme != "https" || url.host != HOST || url.port != 443) return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        val seg = url.pathSegments.let { if (it.size > 1 && it.last().isEmpty()) it.dropLast(1) else it }
        return when {
            seg.size == 2 && seg[0] == "series" -> seg[1].takeIf { s -> s.isNotEmpty() && s.all { it in '0'..'9' } }
                ?.toLongOrNull()?.let(::series)
            seg.isNotEmpty() && seg[0] == "users" -> {
                val user = seg.getOrNull(1)?.takeIf { it.isNotBlank() && !isOrphan(it) } ?: return null
                val rest = seg.drop(2)
                when {
                    rest.isEmpty() || rest == listOf("works") -> authorWorks(AuthorLink(user, user, user))
                    (rest.size == 2 || (rest.size == 3 && rest[2] == "works")) && rest[0] == "pseuds" && rest[1].isNotBlank() ->
                        authorWorks(AuthorLink(rest[1], user, rest[1]))
                    else -> null
                }
            }
            else -> null
        }
    }

    /** A user's own page (`/users/{u}`): where AO3 puts the subscribe form for the user (not on `/users/{u}/works`). */
    fun user(user: String): HttpUrl = "$BASE/".toHttpUrl().newBuilder().addPathSegment("users").addPathSegment(user).build()

    /**
     * The page holding the subscribe form for the list at [url]: the series page, or the user's page for an
     * author's (or pseud's) works. Null when [url] is not an in-app work list ([workList]).
     */
    fun subscribePage(url: HttpUrl): HttpUrl? {
        val list = workList(url) ?: return null
        val seg = list.pathSegments
        return if (seg[0] == "series") list else user(seg[1])
    }

    /** [workList] of a string (e.g. a decoded navigation argument); null when it is not a URL at all. */
    fun workListOrNull(url: String?): HttpUrl? = url?.toHttpUrlOrNull()?.let(::workList)
}
