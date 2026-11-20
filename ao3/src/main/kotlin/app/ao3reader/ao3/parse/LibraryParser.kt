package app.ao3reader.ao3.parse

import app.ao3reader.ao3.model.WorkBlurb
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

@Serializable
data class ReadingExtra(val workId: Long, val lastVisited: String?, val visits: Int?, val updateAvailable: Boolean)

@Serializable
data class BookmarkExtra(
    val workId: Long, val notesHtml: String?, val tags: List<String>, val isPrivate: Boolean, val isRec: Boolean,
)

@Serializable
data class LibraryPage(
    val works: List<WorkBlurb>, val readings: Map<Long, ReadingExtra>, val bookmarks: Map<Long, BookmarkExtra>,
    val page: Int, val hasNext: Boolean,
)

sealed interface Subscription {
    val title: String
    data class Work(val workId: Long, override val title: String, val author: String?) : Subscription
    data class Series(val url: HttpUrl, override val title: String) : Subscription
    data class User(val url: HttpUrl, override val title: String) : Subscription
}

data class SubscriptionPage(val items: List<Subscription>, val page: Int, val hasNext: Boolean)

/** Parsers for the signed-in library pages. Never throw on missing parts. */
object LibraryParser {
    private val visitsRe = Regex("""Visited (once|(\d+) times?)""")
    private val dateRe = Regex("""^\s*(\d{1,2} \w{3} \d{4})""")

    fun readings(html: String, page: Int): LibraryPage {
        val doc = Jsoup.parse(html, Ao3Urls.BASE_URI)
        val works = ArrayList<WorkBlurb>()
        val extras = LinkedHashMap<Long, ReadingExtra>()
        for (li in doc.select("li.blurb")) {
            val w = runCatching { BlurbParser.parseBlurb(li) }.getOrNull() ?: continue
            works += w
            val text = li.selectFirst("div.user.module h4.viewed")?.ownText()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            val visits = visitsRe.find(text)?.let { m -> if (m.groupValues[1] == "once") 1 else m.groupValues[2].toIntOrNull() }
            extras[w.workId] = ReadingExtra(
                w.workId, dateRe.find(text)?.groupValues?.get(1), visits, text.contains("(Update available.)"),
            )
        }
        return LibraryPage(works, extras, emptyMap(), page, hasNext(doc))
    }

    fun bookmarks(html: String, page: Int): LibraryPage {
        val doc = Jsoup.parse(html, Ao3Urls.BASE_URI)
        val works = ArrayList<WorkBlurb>()
        val extras = LinkedHashMap<Long, BookmarkExtra>()
        for (li in doc.select("li.blurb")) {
            val w = runCatching { BlurbParser.parseBlurb(li) }.getOrNull() ?: continue
            works += w
            val own = li.selectFirst("div.user.module")
            val notes = own?.selectFirst("blockquote.notes")?.html()?.trim()?.ifEmpty { null }
            val tags = own?.select("ul.tags a.tag")?.map { it.text().trim() }.orEmpty()
            val status = li.selectFirst("p.status")
            extras[w.workId] = BookmarkExtra(
                w.workId, notes, tags,
                isPrivate = li.selectFirst("span.private") != null,
                isRec = status?.selectFirst("span.rec") != null || li.selectFirst("span.rec") != null,
            )
        }
        return LibraryPage(works, emptyMap(), extras, page, hasNext(doc))
    }

    fun subscriptions(html: String, page: Int, baseUrl: HttpUrl): SubscriptionPage {
        val doc = Jsoup.parse(html, Ao3Urls.BASE_URI)
        val items = doc.select("dl.subscription dt").mapNotNull { runCatching { subscription(it, baseUrl) }.getOrNull() }
        return SubscriptionPage(items, page, hasNext(doc))
    }

    private fun subscription(dt: Element, baseUrl: HttpUrl): Subscription? {
        val a = dt.selectFirst("a[href]") ?: return null
        val href = a.attr("href")
        val title = a.text().trim()
        val url = baseUrl.resolve(href) ?: return null
        return when {
            href.startsWith("/works/") -> {
                val id = href.removePrefix("/works/").takeWhile { it.isDigit() }.toLongOrNull() ?: return null
                Subscription.Work(id, title, dt.selectFirst("a[rel=author]")?.text()?.trim()?.ifEmpty { null })
            }
            href.startsWith("/series/") -> Subscription.Series(url, title)
            href.startsWith("/users/") -> Subscription.User(url, title)
            else -> null
        }
    }

    private fun hasNext(doc: org.jsoup.nodes.Document) = doc.select("ol.pagination li.next a[href]").isNotEmpty()
}
