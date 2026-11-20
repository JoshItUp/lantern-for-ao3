package app.ao3reader.ao3.parse

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

object Ao3Urls {
    private const val BASE = "https://archiveofourown.org"

    /** chapterId null -> /works/{id}. Always carries view_adult=true. */
    fun chapter(workId: Long, chapterId: Long?): HttpUrl {
        val path = if (chapterId == null) "$BASE/works/$workId" else "$BASE/works/$workId/chapters/$chapterId"
        return path.toHttpUrl().newBuilder().addQueryParameter("view_adult", "true").build()
    }

    fun navigate(workId: Long): HttpUrl = "$BASE/works/$workId/navigate".toHttpUrl()

    /** kind: fandom | character | relationship | freeform */
    fun autocomplete(kind: String, term: String): HttpUrl =
        "$BASE/autocomplete/$kind".toHttpUrl().newBuilder().addQueryParameter("term", term).build()
}
