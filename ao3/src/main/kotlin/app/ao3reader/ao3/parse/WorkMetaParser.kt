package app.ao3reader.ao3.parse

import app.ao3reader.ao3.model.WorkBlurb
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Reads a work's metadata (tags, stats, title, byline, summary) from a work or chapter page. Never throws on missing parts. */
object WorkMetaParser {
    fun blurb(doc: Document, workId: Long): WorkBlurb {
        val meta: Element = doc.selectFirst("dl.work.meta") ?: doc
        // Title, byline and work preface, read as for a chapter page.
        val work = ChapterParser.header(doc)

        fun tags(cls: String) = meta.select("dd.$cls.tags a.tag").map { it.text().trim() }
        val (posted, total) = BlurbParser.chapterCounts(meta)
        val series = ChapterParser.seriesLinks(meta.select("dd.series span.position"))
        fun stat(cls: String) = BlurbParser.stat(meta, cls)

        return WorkBlurb(
            workId = workId, title = work.title, authors = work.authors, fandoms = tags("fandom"),
            rating = tags("rating").firstOrNull(), warnings = tags("warning"), categories = tags("category"),
            complete = posted?.let { it == total },
            relationships = tags("relationship"), characters = tags("character"), freeforms = tags("freeform"),
            summaryHtml = work.preface?.selectFirst("div.summary blockquote.userstuff")?.html()?.trim()?.ifEmpty { null },
            language = meta.selectFirst("dd.language")?.text()?.trim()?.ifEmpty { null },
            words = BlurbParser.statNum(meta, "words"), chaptersPosted = posted, chaptersTotal = total,
            kudos = BlurbParser.statNum(meta, "kudos"), hits = BlurbParser.statNum(meta, "hits"),
            bookmarks = BlurbParser.statNum(meta, "bookmarks"), comments = BlurbParser.statNum(meta, "comments"),
            updated = (stat("status") ?: stat("published"))?.text()?.trim()?.ifEmpty { null },
            authorLinks = work.authorLinks, series = series,
        )
    }

    /** Chapters posted so far from the stats' "7/12" or "7/?"; null when the page has none. */
    fun postedChapters(html: String): Int? = BlurbParser.chapterCounts(Jsoup.parse(html)).first
}
