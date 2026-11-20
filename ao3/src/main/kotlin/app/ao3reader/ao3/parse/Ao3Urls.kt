package app.ao3reader.ao3.parse

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

object Ao3Urls {
    private const val BASE = "https://archiveofourown.org"
    /** Base URI for parsing AO3 HTML, so relative links resolve (`Element.absUrl`). */
    const val BASE_URI = "$BASE/"

    /** chapterId null -> /works/{id}. Always carries view_adult=true. */
    fun chapter(workId: Long, chapterId: Long?): HttpUrl {
        val path = if (chapterId == null) "$BASE/works/$workId" else "$BASE/works/$workId/chapters/$chapterId"
        return path.toHttpUrl().newBuilder().addQueryParameter("view_adult", "true").build()
    }

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
}
