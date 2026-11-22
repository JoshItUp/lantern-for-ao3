package io.github.joshitup.lantern.ao3.parse

import io.github.joshitup.lantern.ao3.model.ChapterRef
import org.jsoup.Jsoup

object NavigateParser {
    private val prefix = Regex("^\\s*(\\d+)\\.\\s*")

    fun parse(html: String): List<ChapterRef> {
        val doc = Jsoup.parse(html)
        val out = ArrayList<ChapterRef>()
        for (a in doc.select("ol.chapter.index li a[href*=/chapters/]")) {
            val id = a.attr("href").substringAfter("/chapters/").takeWhile { it.isDigit() }.toLongOrNull() ?: continue
            val text = a.text().trim()
            val m = prefix.find(text)
            val number = m?.groupValues?.get(1)?.toIntOrNull() ?: (out.size + 1)
            out.add(ChapterRef(id, number, text.removeRange(0, m?.value?.length ?: 0).trim()))
        }
        return out
    }
}
