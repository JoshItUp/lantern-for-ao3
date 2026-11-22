package app.ao3reader.ao3.parse

import app.ao3reader.ao3.model.WorkBlurb
import app.ao3reader.ao3.model.WorkPage
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Parses any AO3 work listing (search, history, bookmarks, tag pages). Never throws on missing elements. */
object BlurbParser {
    fun parsePage(html: String, page: Int): WorkPage = parsePage(Jsoup.parse(html), page)

    fun parsePage(doc: Document, page: Int): WorkPage {
        val works = doc.select("li.blurb").mapNotNull { runCatching { parseBlurb(it) }.getOrNull() }
        return WorkPage(works, page, hasNext(doc))
    }

    /** The listing's pagination has a "Next" link. */
    internal fun hasNext(doc: Document): Boolean = doc.select("ol.pagination li.next a[href]").isNotEmpty()

    /** One `li.blurb` -> WorkBlurb; null when it is not a work (series/mystery/deleted). */
    fun parseBlurb(li: Element): WorkBlurb? {
        val workId: Long = li.id().removePrefix("work_").toLongOrNull()
            ?: li.selectFirst("h4.heading a[href^=/works/]")?.attr("href")
                ?.removePrefix("/works/")?.takeWhile { it.isDigit() }?.toLongOrNull()
            ?: return null
        val heading = li.selectFirst("h4.heading")
        val title = heading?.selectFirst("a[href^=/works/]")?.text()?.trim().orEmpty()
        val authorAnchors: List<Element> = heading?.select("a[rel=author]").orEmpty()
        val authorNames = authorAnchors.map { it.text().trim() }
        val authors = authorNames.ifEmpty {
            val t = heading?.ownText()?.trim().orEmpty()
            if (t.startsWith("by ")) listOf(t.removePrefix("by ").trim()).filter { it.isNotEmpty() }
            else if (t.isNotEmpty() && heading?.selectFirst("a[href^=/works/]") != null) listOf(t.removePrefix("by").trim()).filter { it.isNotEmpty() }
            else emptyList()
        }
        val fandoms = li.select("h5.fandoms a.tag").map { it.text().trim() }

        fun req(cls: String): String? {
            val el: Element = li.selectFirst("ul.required-tags span.$cls") ?: return null
            val v: String = el.attr("title").ifBlank { el.selectFirst(".text")?.text().orEmpty() }.trim()
            return v.ifEmpty { null }
        }
        val rating = req("rating")
        val warnings = req("warnings")?.split(", ")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val categories = req("category")?.split(", ")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val complete = req("iswip")?.let { it.equals("Complete Work", true) }

        fun tags(cls: String) = li.select("ul.tags li.$cls a.tag").map { it.text().trim() }
        val summary: String? = li.select("blockquote.summary")
            .firstOrNull { q -> q.parents().none { it.tagName() == "div" && it.hasClass("user") && it.hasClass("module") } }
            ?.html()?.trim()?.ifEmpty { null }

        fun stat(cls: String): Element? = stat(li, cls)
        fun num(cls: String): Int? = statNum(li, cls)
        val (posted, total) = chapterCounts(li)

        return WorkBlurb(
            workId = workId, title = title, authors = authors, fandoms = fandoms,
            rating = rating, warnings = warnings, categories = categories, complete = complete,
            relationships = tags("relationships"), characters = tags("characters"), freeforms = tags("freeforms"),
            summaryHtml = summary, language = stat("language")?.text()?.trim()?.ifEmpty { null },
            words = num("words"), chaptersPosted = posted, chaptersTotal = total,
            kudos = num("kudos"), hits = num("hits"), bookmarks = num("bookmarks"), comments = num("comments"),
            updated = li.selectFirst("p.datetime")?.text()?.trim()?.ifEmpty { null },
            authorLinks = authorAnchors.mapNotNull { Links.author(it) },
            series = li.select("ul.series li").mapNotNull { s: Element ->
                val a: Element = s.selectFirst("a[href]") ?: return@mapNotNull null
                Links.series(a, s.selectFirst("strong")?.text()?.trim()?.toIntOrNull())
            },
        )
    }

    /** The `dd.{cls}` of the `dl.stats` inside [scope] (a blurb, or a work page's meta). */
    internal fun stat(scope: Element, cls: String): Element? = scope.selectFirst("dl.stats dd.$cls")

    internal fun statNum(scope: Element, cls: String): Int? = stat(scope, cls)?.text()?.replace(",", "")?.trim()?.toIntOrNull()

    /** "7/12" -> 7 to 12; "7/?" -> 7 to null; either null when absent. */
    internal fun chapterCounts(scope: Element): Pair<Int?, Int?> {
        val parts = stat(scope, "chapters")?.text()?.trim()?.split("/")
        fun n(i: Int) = parts?.getOrNull(i)?.replace(",", "")?.trim()?.toIntOrNull()
        return n(0) to n(1)
    }
}
