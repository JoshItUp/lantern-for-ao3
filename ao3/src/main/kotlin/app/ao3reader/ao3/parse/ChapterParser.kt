package app.ao3reader.ao3.parse

import app.ao3reader.ao3.model.AuthorLink
import app.ao3reader.ao3.model.Block
import app.ao3reader.ao3.model.ChapterContent
import app.ao3reader.ao3.model.ChapterRef
import app.ao3reader.ao3.model.SeriesLink
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

    /** Parses a single chapter page (or a work page with one chapter): `#chapters` is the chapter. */
    internal fun parse(doc: Document, workId: Long): ChapterContent {
        val work = header(doc)
        val chapterEl: Element? = work.skin.selectFirst("#chapters")
        val chapterPreface: Element? = chapterEl?.selectFirst("div.chapter.preface")
        val selected = doc.selectFirst("#selected_id option[selected]")
        val chapterId: Long? = selected?.attr("value")?.trim()?.toLongOrNull() ?: chapterId(chapterPreface)
        val number: Int = selected?.let { s: Element ->
            numPrefix.find(s.text())?.groupValues?.get(1)?.toIntOrNull()
                ?: s.parent()?.children()?.indexOf(s)?.plus(1)
        } ?: work.skin.selectFirst("#chapters div.chapter[id^=chapter-]")?.id()?.removePrefix("chapter-")?.toIntOrNull()
            ?: 1
        val chapterTitle = chapterTitle(chapterPreface)
            ?: selected?.text()?.let { numPrefix.replaceFirst(it, "").trim() }?.takeIf { it.isNotEmpty() }
        val ref = if (chapterId == null) ChapterRef(null, 1, work.title)
        else ChapterRef(chapterId, number, chapterTitle ?: work.title)
        // A chapter page shows the work end notes only on the last chapter, so whatever is there belongs here.
        return chapter(workId, work, chapterEl, ref.number, isLast = true, ref = ref)
    }

    /** The work-level parts of a work or chapter page, shared by every chapter on it. */
    internal class WorkHeader(
        val skin: Element, val preface: Element?, val title: String, val authors: List<String>,
        val authorLinks: List<AuthorLink>, val series: List<SeriesLink>,
    )

    internal fun header(doc: Document): WorkHeader {
        val skin: Element = doc.selectFirst("#workskin") ?: doc.body()
        val workPreface: Element? = skin.select("div.preface.group").firstOrNull { e: Element -> e.closest("#chapters") == null }

        val workTitle = (workPreface?.selectFirst("h2.title")?.text()
            ?: skin.selectFirst("h2.title")?.text()
            ?: doc.title().substringBefore(" - ")).trim()
        val byline = workPreface?.selectFirst("h3.byline") ?: skin.selectFirst("h3.byline")
        val authorAnchors: List<Element> = byline?.select("a[rel=author]").orEmpty()
        val authors = authorAnchors.map { it.text().trim() }.filter { it.isNotEmpty() }
            .ifEmpty { listOfNotNull(byline?.text()?.trim()?.takeIf { it.isNotEmpty() }) }
        // Work meta (outside #workskin).
        val series = seriesLinks(doc.select("dl.work.meta dd.series span.position"))
        return WorkHeader(skin, workPreface, workTitle, authors, authorAnchors.mapNotNull { Links.author(it) }, series)
    }

    /** The work meta's "Part N of <series>" entries, one `span.position` per series. */
    internal fun seriesLinks(positions: List<Element>): List<SeriesLink> = positions.mapNotNull { pos: Element ->
        val part = pos.ownText().substringAfter("Part", "").trim().substringBefore(' ').toIntOrNull()
        val a: Element = pos.selectFirst("a[href]") ?: return@mapNotNull null
        Links.series(a, part)
    }

    /** The id in a chapter preface's `h3.title` link to `/chapters/{id}`. */
    internal fun chapterId(chapterPreface: Element?): Long? =
        chapterPreface?.selectFirst("h3.title a[href*=/chapters/]")?.attr("href")
            ?.substringAfter("/chapters/")?.takeWhile { it.isDigit() }?.toLongOrNull()

    internal fun chapterTitle(chapterPreface: Element?): String? =
        chapterPreface?.selectFirst("h3.title")?.text()?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * One chapter's content. The body and chapter notes come only from [chapterEl]. The work summary
     * and work begin notes ([WorkHeader.preface]) go on chapter [number] 1; the work end notes (end
     * notes outside `#chapters`, i.e. `#work_endnotes`) only when [isLast].
     */
    internal fun chapter(
        workId: Long, work: WorkHeader, chapterEl: Element?, number: Int, isLast: Boolean, ref: ChapterRef,
    ): ChapterContent {
        val workPreface = work.preface
        val chapterPreface: Element? = chapterEl?.selectFirst("div.chapter.preface")

        val bodyEl: Element? = chapterEl?.selectFirst("div.userstuff.module") ?: chapterEl?.selectFirst("div.userstuff")
        val body = bodyEl?.let { b ->
            val copy = b.clone()
            copy.select(".landmark, h3.heading#work").remove()
            BlockConverter.convert(copy)
        }.orEmpty()

        fun notes(els: List<Element>): List<Block> = els.flatMap { el: Element -> BlockConverter.convert(el) }
        fun notes(scope: Element?, sel: String): List<Block> = notes(scope?.select(sel).orEmpty())

        val chapterNotes = notes(chapterPreface, ".notes.module:not(.end) blockquote.userstuff")
        val workNotes = if (number == 1) notes(workPreface, ".notes.module:not(.end) blockquote.userstuff") else emptyList()
        // AO3 only shows the work summary on chapter 1's page.
        val summary = if (number == 1) notes(workPreface, "div.summary blockquote.userstuff") else emptyList()
        // The chapter's own summary leads the chapter's begin notes, marked with a heading (notes render
        // dimmed), unless it just repeats the work summary.
        val chapterSummary = notes(chapterPreface, "div.summary blockquote.userstuff")
            .takeIf { it.isNotEmpty() && it != summary }
            ?.let { listOf(Block.Heading("Summary", 4)) + it }
            .orEmpty()
        // Document order: work notes (chapter 1), then the chapter's summary and notes.
        val beginNotes = workNotes + chapterSummary + chapterNotes
        // The chapter's end notes, then (last chapter) the work end notes, as in document order.
        val chapterEnd = notes(chapterEl, END_NOTES)
        val workEnd = if (isLast) notes(work.skin.select(END_NOTES).filter { it.closest("#chapters") == null }) else emptyList()

        return ChapterContent(
            workId, ref, work.title, work.authors, beginNotes, body, chapterEnd + workEnd, summary,
            authorLinks = work.authorLinks, series = work.series,
        )
    }

    private const val END_NOTES = "div.end.notes.module blockquote.userstuff"
}
