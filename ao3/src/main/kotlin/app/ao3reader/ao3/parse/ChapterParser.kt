package app.ao3reader.ao3.parse

import app.ao3reader.ao3.model.Block
import app.ao3reader.ao3.model.ChapterContent
import app.ao3reader.ao3.model.ChapterRef
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Parses a work/chapter page. Never throws on missing parts; unknowns become empty. */
object ChapterParser {
    private val numPrefix = Regex("^\\s*(\\d+)\\.\\s*")

    private const val CHAPTER_BODY = "#chapters div.userstuff"

    /** True only if the page has a `#chapters` container with a `userstuff` body. Used where the page is not otherwise parsed. */
    fun looksLikeChapterPage(html: String): Boolean =
        html.contains("chapters") && Jsoup.parse(html).selectFirst(CHAPTER_BODY) != null

    /**
     * Parses [html] once; null unless it is a chapter page ([looksLikeChapterPage]) with a non-empty
     * body. What the reader uses, so a load costs one parse.
     */
    fun parseOrNull(html: String, workId: Long): ChapterContent? {
        if (!html.contains("chapters")) return null
        val doc = document(html)
        if (doc.selectFirst(CHAPTER_BODY) == null) return null
        return parse(doc, workId).takeIf { it.body.isNotEmpty() }
    }

    fun parse(html: String, workId: Long): ChapterContent = parse(document(html), workId)

    /** With AO3 as the base URI, so relative links in fic text resolve. */
    private fun document(html: String): Document = Jsoup.parse(html, Ao3Urls.BASE_URI)

    private fun parse(doc: Document, workId: Long): ChapterContent {
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
            scope?.select(sel).orEmpty().flatMap { el: Element -> BlockConverter.convert(el) }

        val chapterNotes = notes(chapterPreface, ".notes.module:not(.end) blockquote.userstuff")
        val workNotes = if (ref.number == 1) notes(workPreface, ".notes.module:not(.end) blockquote.userstuff") else emptyList()
        // AO3 only shows the work summary on chapter 1's page.
        val summary = if (ref.number == 1) notes(workPreface, "div.summary blockquote.userstuff") else emptyList()
        // The chapter's own summary leads the chapter's begin notes, marked with a heading (notes render
        // dimmed), unless it just repeats the work summary.
        val chapterSummary = notes(chapterPreface, "div.summary blockquote.userstuff")
            .takeIf { it.isNotEmpty() && it != summary }
            ?.let { listOf(Block.Heading("Summary", 4)) + it }
            .orEmpty()
        // Document order: work notes (chapter 1), then the chapter's summary and notes.
        val beginNotes = workNotes + chapterSummary + chapterNotes
        // chapter end notes (inside #chapters) precede work end notes (#work_endnotes) in document order
        val endNotes = notes(skin, "div.end.notes.module blockquote.userstuff")

        return ChapterContent(workId, ref, workTitle, authors, beginNotes, body, endNotes, summary)
    }
}
