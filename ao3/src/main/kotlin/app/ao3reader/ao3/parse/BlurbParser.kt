package app.ao3reader.ao3.parse

import app.ao3reader.ao3.model.WorkBlurb
import app.ao3reader.ao3.model.WorkPage
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Parses any AO3 work listing (search, history, bookmarks, tag pages). Never throws on missing elements. */
object BlurbParser {
    fun parsePage(html: String, page: Int): WorkPage {
        val doc = Jsoup.parse(html)
        val works = doc.select("li.blurb").mapNotNull { runCatching { parseBlurb(it) }.getOrNull() }
        val hasNext = doc.select("ol.pagination li.next a[href]").isNotEmpty()
        return WorkPage(works, page, hasNext)
    }

    /** One `li.blurb` -> WorkBlurb; null when it is not a work (series/mystery/deleted). */
    fun parseBlurb(li: Element): WorkBlurb? {
        val workId: Long = li.id().removePrefix("work_").toLongOrNull()
            ?: li.selectFirst("h4.heading a[href^=/works/]")?.attr("href")
                ?.removePrefix("/works/")?.takeWhile { it.isDigit() }?.toLongOrNull()
            ?: return null
        val heading = li.selectFirst("h4.heading")
        val title = heading?.selectFirst("a[href^=/works/]")?.text()?.trim().orEmpty()
        val authorLinks = heading?.select("a[rel=author]").orEmpty().map { it.text().trim() }
        val authors = authorLinks.ifEmpty {
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

        fun stat(cls: String): Element? = li.selectFirst("dl.stats dd.$cls")
        fun num(cls: String): Int? = stat(cls)?.text()?.replace(",", "")?.trim()?.toIntOrNull()
        val chapters = stat("chapters")?.text()?.trim()?.split("/")
        val posted = chapters?.getOrNull(0)?.replace(",", "")?.trim()?.toIntOrNull()
        val total = chapters?.getOrNull(1)?.replace(",", "")?.trim()?.toIntOrNull()

        return WorkBlurb(
            workId = workId, title = title, authors = authors, fandoms = fandoms,
            rating = rating, warnings = warnings, categories = categories, complete = complete,
            relationships = tags("relationships"), characters = tags("characters"), freeforms = tags("freeforms"),
            summaryHtml = summary, language = stat("language")?.text()?.trim()?.ifEmpty { null },
            words = num("words"), chaptersPosted = posted, chaptersTotal = total,
            kudos = num("kudos"), hits = num("hits"), bookmarks = num("bookmarks"), comments = num("comments"),
            updated = li.selectFirst("p.datetime")?.text()?.trim()?.ifEmpty { null },
        )
    }
}
