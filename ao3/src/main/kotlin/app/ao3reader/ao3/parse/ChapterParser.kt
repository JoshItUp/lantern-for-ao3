package app.ao3reader.ao3.parse

import app.ao3reader.ao3.model.Block
import app.ao3reader.ao3.model.ChapterContent
import app.ao3reader.ao3.model.ChapterRef
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Parses a work/chapter page. Never throws on missing parts; unknowns become empty. */
object ChapterParser {
    private val numPrefix = Regex("^\\s*(\\d+)\\.\\s*")

    fun parse(html: String, workId: Long): ChapterContent {
        val doc = Jsoup.parse(html)
        val skin: Element = doc.selectFirst("#workskin") ?: doc.body()
        val workPreface: Element? = skin.select("div.preface.group").firstOrNull { e: Element -> e.closest("#chapters") == null }

        val workTitle = (workPreface?.selectFirst("h2.title")?.text()
            ?: skin.selectFirst("h2.title")?.text()
            ?: doc.title().substringBefore(" - ")).trim()
        val byline = workPreface?.selectFirst("h3.byline") ?: skin.selectFirst("h3.byline")
        val authors = byline?.select("a[rel=author]")?.map { it.text().trim() }?.filter { it.isNotEmpty() }.orEmpty()
            .ifEmpty { listOfNotNull(byline?.text()?.trim()?.takeIf { it.isNotEmpty() }) }

        val chapterPreface: Element? = skin.selectFirst("#chapters div.chapter.preface")
        val selected = doc.selectFirst("#selected_id option[selected]")
        val titleLink = chapterPreface?.selectFirst("h3.title a[href*=/chapters/]")
        val chapterId: Long? = selected?.attr("value")?.trim()?.toLongOrNull()
            ?: titleLink?.attr("href")?.substringAfter("/chapters/")?.takeWhile { it.isDigit() }?.toLongOrNull()
        val number: Int = selected?.let { s: Element ->
            numPrefix.find(s.text())?.groupValues?.get(1)?.toIntOrNull()
                ?: s.parent()?.children()?.indexOf(s)?.plus(1)
        } ?: skin.selectFirst("#chapters div.chapter[id^=chapter-]")?.id()?.removePrefix("chapter-")?.toIntOrNull()
            ?: 1
        val chapterTitle = chapterPreface?.selectFirst("h3.title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: selected?.text()?.let { numPrefix.replaceFirst(it, "").trim() }?.takeIf { it.isNotEmpty() }
        val ref = if (chapterId == null) ChapterRef(null, 1, workTitle)
        else ChapterRef(chapterId, number, chapterTitle ?: workTitle)

        val bodyEl: Element? = skin.selectFirst("#chapters div.userstuff.module") ?: skin.selectFirst("#chapters div.userstuff")
        val body = bodyEl?.let { b ->
            val copy = b.clone()
            copy.select(".landmark, h3.heading#work").remove()
            BlockConverter.convert(copy)
        }.orEmpty()

        fun notes(scope: Element?, sel: String): List<Block> =
            scope?.selectFirst(sel)?.let { el: Element -> BlockConverter.convert(el) }.orEmpty()

        val chapterNotes = notes(chapterPreface, ".notes.module:not(.end) blockquote.userstuff")
        val beginNotes = chapterNotes.ifEmpty {
            if (ref.number == 1) notes(workPreface, ".notes.module:not(.end) blockquote.userstuff") else emptyList()
        }
        val endNotes = notes(skin, "div.end.notes.module blockquote.userstuff")
        val summary = if (ref.number == 1) notes(workPreface, "div.summary blockquote.userstuff") else emptyList()

        return ChapterContent(workId, ref, workTitle, authors, beginNotes, body, endNotes, summary)
    }
}
