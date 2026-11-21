package app.ao3reader.ao3.parse

import okhttp3.HttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * The signed-in account's action forms on a work (chapter) page, and what they say about the work's
 * state. Every form is POSTed as parsed (FormParser contract).
 */
data class WorkActions(
    val kudos: Ao3Form?,
    /** Best effort: the signed-in name is in the kudos list (AO3 may truncate long lists). */
    val kudosGiven: Boolean,
    /** Create form (`/works/{id}/bookmarks`) or, when [bookmarked], the edit form (`/bookmarks/{id}`, `_method=put`). */
    val bookmarkForm: Ao3Form?,
    val bookmarked: Boolean,
    val bookmarkEditUrl: HttpUrl?,
    /** `/works/{id}/mark_for_later`, or `/works/{id}/mark_as_read` when [markedForLater]. */
    val markForLater: Ao3Form?,
    val markedForLater: Boolean,
    /** Create (`/users/{me}/subscriptions`) or, when [subscribed], delete (`/users/{me}/subscriptions/{id}`, `_method=delete`). */
    val subscribe: Ao3Form?,
    val subscribed: Boolean,
    val comment: Ao3Form?,
)

/** The subscribe form on a series page (`/series/{id}`) or a user page (`/users/{u}`). */
data class ListActions(val subscribe: Ao3Form?, val subscribed: Boolean)

/** The owner's bookmark: notes, tags (comma-separated on AO3), private, rec. */
data class BookmarkInput(val notes: String, val tags: List<String>, val isPrivate: Boolean, val isRec: Boolean)

object WorkActionsParser {
    const val NOTES = "bookmark[bookmarker_notes]"
    const val TAGS = "bookmark[tag_string]"
    const val PRIVATE = "bookmark[private]"
    const val REC = "bookmark[rec]"
    const val COMMENT = "comment[comment_content]"

    private val bookmarkAction = Regex("^/bookmarks/(\\d+)/?$")
    private val bookmarkEditHref = Regex("^(?:https?://[^/]+)?/bookmarks/(\\d+)/edit/?(?:[?#].*)?$")
    private val newBookmarkAction = Regex("^/works/\\d+/bookmarks/?$")
    private val userHref = Regex("^(?:https?://[^/]+)?/users/([^/?#]+)/?(?:[?#].*)?$")
    private const val SUBSCRIBE_TYPE = "subscription[subscribable_type]"

    fun parse(html: String, baseUrl: HttpUrl): WorkActions {
        val doc = Jsoup.parse(html)
        val user = AccountParser.state(doc).username

        val kudosEl: Element? = doc.selectFirst("form#new_kudo") ?: doc.select("form[action]").firstOrNull { path(it, baseUrl) == "/kudos" }
        val kudosGiven = user != null && doc.select("#kudos a[href]").any { a ->
            userHref.find(a.attr("href"))?.groupValues?.get(1)?.let { decode(it).equals(user, ignoreCase = true) } == true
        }

        val bookmark = bookmarkFormIn(doc, baseUrl)
        val bookmarkId = bookmark?.action?.encodedPath?.let { bookmarkAction.find(it)?.groupValues?.get(1) }
        val editHref: Element? = doc.select("a[href]").firstNotNullOfOrNull { a -> bookmarkEditHref.find(a.attr("href"))?.let { a } }
        val editUrl = when {
            bookmarkId != null -> baseUrl.resolve("/bookmarks/$bookmarkId/edit")
            editHref != null -> baseUrl.resolve(editHref.attr("href"))
            else -> null
        }
        val editLinkText = doc.select("a.bookmark_form_placement_open").any { it.text().trim().equals("Edit Bookmark", ignoreCase = true) }
        val bookmarked = bookmarkId != null || bookmark?.method == "put" || editHref != null || editLinkText

        val mflEl: Element? = doc.select("form[action]").firstOrNull { f ->
            val p = path(f, baseUrl)
            p.endsWith("/mark_for_later") || p.endsWith("/mark_as_read")
        }
        val mfl = mflEl?.let { FormParser.parse(it, baseUrl) }

        val subEl: Element? = subscribeForms(doc).firstOrNull { typeOf(it) == "Work" }
        val sub = subEl?.let { FormParser.parse(it, baseUrl) }

        val commentEl: Element? = doc.select("form.new_comment[action]").firstOrNull { path(it, baseUrl).endsWith("/comments") }

        return WorkActions(
            kudos = kudosEl?.let { FormParser.parse(it, baseUrl) },
            kudosGiven = kudosGiven,
            bookmarkForm = bookmark,
            bookmarked = bookmarked,
            bookmarkEditUrl = editUrl,
            markForLater = mfl,
            markedForLater = mfl != null && mfl.action.encodedPath.endsWith("/mark_as_read"),
            subscribe = sub,
            subscribed = sub != null && isSubscribed(sub),
            comment = commentEl?.let { FormParser.parse(it, baseUrl) },
        )
    }

    /** Series or user subscribe form; work subscribe forms are ignored. */
    fun parseList(html: String, baseUrl: HttpUrl): ListActions {
        val el: Element = subscribeForms(Jsoup.parse(html)).firstOrNull { typeOf(it) == "Series" || typeOf(it) == "User" }
            ?: return ListActions(null, false)
        val f = FormParser.parse(el, baseUrl)
        return ListActions(f, isSubscribed(f))
    }

    /** The bookmark form on a work page or a bookmark edit page. */
    fun bookmarkForm(html: String, baseUrl: HttpUrl): Ao3Form? = bookmarkFormIn(Jsoup.parse(html), baseUrl)

    /** The comment form for [chapterId] (any comment form when null). */
    fun commentForm(html: String, baseUrl: HttpUrl, chapterId: Long?): Ao3Form? {
        val forms = Jsoup.parse(html).select("form.new_comment[action]").filter { path(it, baseUrl).endsWith("/comments") }
        val el: Element? = if (chapterId == null) forms.firstOrNull() else forms.firstOrNull { path(it, baseUrl) == "/chapters/$chapterId/comments" }
        return el?.let { FormParser.parse(it, baseUrl) }
    }

    /** The bookmark as the form holds it. Rails checkbox pairs: the last value wins. */
    fun bookmarkInput(form: Ao3Form): BookmarkInput = BookmarkInput(
        notes = form.last(NOTES).orEmpty(),
        tags = form.last(TAGS).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
        isPrivate = form.last(PRIVATE) == "1",
        isRec = form.last(REC) == "1",
    )

    /** The work a parsed page belongs to, from its kudos or subscribe form. */
    fun workId(a: WorkActions): Long? =
        a.kudos?.last("kudo[commentable_id]")?.toLongOrNull()
            ?: a.subscribe?.takeIf { it.last(SUBSCRIBE_TYPE) == "Work" }?.last("subscription[subscribable_id]")?.toLongOrNull()

    /** A flash error (including AO3's comment/kudos errors) or a Rails error box; null when none. */
    fun actionError(html: String): String? {
        val el: Element = Jsoup.parse(html).selectFirst(".flash.error, .flash.comment_error, .flash.kudos_error, #error, div.error") ?: return null
        return el.text().trim().ifEmpty { null }
    }

    /** AO3's "You have already left kudos here" answer, in a flash of any kind. */
    fun kudosAlreadyLeft(html: String): Boolean =
        Jsoup.parse(html).select(".flash, #error, div.error, #kudos_message").any { it.text().contains("already left kudos", ignoreCase = true) }

    private fun bookmarkFormIn(doc: Document, baseUrl: HttpUrl): Ao3Form? {
        val el: Element? = doc.selectFirst("#bookmark-form form[action]")
            ?: doc.select("form[action]").firstOrNull { f ->
                val p = path(f, baseUrl)
                (bookmarkAction.matches(p) || newBookmarkAction.matches(p)) && f.selectFirst("[name=$NOTES]") != null
            }
        return el?.let { FormParser.parse(it, baseUrl) }
    }

    private fun subscribeForms(doc: Document): List<Element> =
        doc.select("form[action*=/subscriptions]").filter { it.selectFirst("input[name=$SUBSCRIBE_TYPE]") != null }

    private fun typeOf(form: Element): String? = form.selectFirst("input[name=$SUBSCRIBE_TYPE]")?.attr("value")

    private fun isSubscribed(f: Ao3Form): Boolean =
        f.last("_method").equals("delete", ignoreCase = true) || Regex("/subscriptions/\\d+/?$").containsMatchIn(f.action.encodedPath)

    private fun path(form: Element, baseUrl: HttpUrl): String =
        baseUrl.resolve(form.attr("action"))?.encodedPath ?: ""

    private fun decode(s: String): String = try {
        java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    } catch (_: IllegalArgumentException) {
        s
    }
}

/** The last value of [name] (Rails reads the last of repeated names). */
fun Ao3Form.last(name: String): String? = fields.lastOrNull { it.first == name }?.second
