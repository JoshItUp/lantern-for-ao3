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

    fun navigate(workId: Long): HttpUrl = "$BASE/works/$workId/navigate".toHttpUrl()

    /** kind: fandom | character | relationship | freeform */
    fun autocomplete(kind: String, term: String): HttpUrl =
        "$BASE/autocomplete/$kind".toHttpUrl().newBuilder().addQueryParameter("term", term).build()
}
