package io.github.joshitup.lantern.ao3.parse

import io.github.joshitup.lantern.ao3.model.WorkPage
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** A series page or an author's works page, parsed once. [seriesTitle] is null on author pages. */
data class WorkListPage(val page: WorkPage, val seriesTitle: String?)

/**
 * Series pages (`ul.series.work.index li.work.blurb`, in series order) and author works pages
 * (`ol.work.index li.work.blurb`) are ordinary blurb listings.
 */
object WorkListParser {
    fun parse(html: String, page: Int): WorkListPage {
        val doc = Jsoup.parse(html)
        return WorkListPage(BlurbParser.parsePage(doc, page), seriesTitle(doc))
    }

    private fun seriesTitle(doc: Document): String? {
        if (doc.selectFirst("#main.series-show, ul.series.work.index") == null) return null
        return doc.selectFirst("#main h2.heading")?.text()?.trim()?.ifEmpty { null }
    }
}
