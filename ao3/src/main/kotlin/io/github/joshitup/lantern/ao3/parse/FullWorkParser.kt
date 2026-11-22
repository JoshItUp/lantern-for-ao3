package io.github.joshitup.lantern.ao3.parse

import io.github.joshitup.lantern.ao3.model.ChapterContent
import io.github.joshitup.lantern.ao3.model.ChapterRef
import io.github.joshitup.lantern.ao3.model.WorkBlurb
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** What a full-work page says about the work besides the chapter texts. */
data class FullWorkMeta(val blurb: WorkBlurb, val chapters: List<ChapterRef>)

/** Parses AO3's whole-work page (`/works/{id}?view_full_work=true`). Never throws on missing parts. */
object FullWorkParser {
    /**
     * Null if [html] is not a work page (e.g. the sign-in page). Otherwise calls [onChapter] once per
     * chapter in reading order, so the caller can store each and drop it. The HTML is parsed once.
     */
    fun parse(html: String, workId: Long, onChapter: (ChapterContent) -> Unit): FullWorkMeta? {
        if (!html.contains("chapters")) return null
        val doc = Jsoup.parse(html, Ao3Urls.BASE_URI)
        if (doc.selectFirst("#chapters div.userstuff") == null) return null
        val blurb = WorkMetaParser.blurb(doc, workId)

        val work = ChapterParser.header(doc)
        val chapterEls: List<Element> = work.skin.select("#chapters > div.chapter[id^=chapter-]")
        if (chapterEls.isEmpty()) {
            // A one-chapter work has no per-chapter wrappers: the same as its chapter page.
            val only = ChapterParser.parse(doc, workId)
            onChapter(only)
            return FullWorkMeta(blurb, listOf(only.ref))
        }

        val refs = chapterEls.mapIndexed { i, el ->
            val number = i + 1
            val preface: Element? = el.selectFirst("div.chapter.preface")
            val ref = ChapterRef(ChapterParser.chapterId(preface), number, ChapterParser.chapterTitle(preface) ?: work.title)
            onChapter(ChapterParser.chapter(workId, work, el, number, isLast = i == chapterEls.lastIndex, ref = ref))
            ref
        }
        return FullWorkMeta(blurb, refs)
    }
}
