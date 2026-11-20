package app.ao3reader.ui.common

import androidx.compose.runtime.Immutable
import app.ao3reader.ao3.model.AuthorLink
import app.ao3reader.ao3.model.ChapterContent
import app.ao3reader.ao3.model.SeriesLink
import app.ao3reader.ao3.model.WorkBlurb
import app.ao3reader.ao3.parse.Ao3Urls
import okhttp3.HttpUrl

/** Opens the in-app list of works for a series or author (AO3 https list URLs only). */
typealias OpenWorkList = (url: HttpUrl, title: String) -> Unit

/** One author or series on a card/header. [url] null: shown as plain text (anonymous, orphaned, or the list on screen). */
@Immutable
data class WorkLink(val label: String, val url: HttpUrl?, val title: String)

/** A work's author and series links, built once off the main thread (no URL work in composition). */
@Immutable
data class CardLinks(val authors: List<WorkLink>, val series: List<WorkLink>) {
    val tappable: Boolean get() = authors.any { it.url != null } || series.any { it.url != null }
}

/**
 * Links for a byline ([authors], in order) and its series. Each author name takes the next unused link
 * with that text; names without one (Anonymous, orphan_account) stay plain. Links to [current] (the
 * list being shown) are plain too, so a card never reopens the screen it sits on.
 */
fun cardLinks(authors: List<String>, authorLinks: List<AuthorLink>, series: List<SeriesLink>, current: HttpUrl? = null): CardLinks {
    val unused = authorLinks.toMutableList()
    val a = authors.map { name ->
        val link = unused.firstOrNull { it.name == name }?.also { unused.remove(it) }
        val url = link?.let { Ao3Urls.authorWorks(it) }?.takeIf { it != current }
        WorkLink(name, url, if (link != null) "Works by $name" else name)
    }
    val s = series.map { sl ->
        val title = sl.title.ifBlank { "Series" }
        val url = Ao3Urls.series(sl.id).takeIf { it != current }
        WorkLink(sl.part?.let { "Part $it of $title" } ?: title, url, title)
    }
    return CardLinks(a, s)
}

fun WorkBlurb.cardLinks(current: HttpUrl? = null): CardLinks = cardLinks(authors, authorLinks, series, current)

fun ChapterContent.headerLinks(): CardLinks = cardLinks(authors, authorLinks, series)
