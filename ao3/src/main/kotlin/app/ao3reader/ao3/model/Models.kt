package app.ao3reader.ao3.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Rating(val id: Int, val label: String) {
    NOT_RATED(9, "Not Rated"), GENERAL(10, "General Audiences"), TEEN(11, "Teen And Up Audiences"),
    MATURE(12, "Mature"), EXPLICIT(13, "Explicit")
}

@Serializable
enum class Warning(val id: Int, val label: String) {
    CHOSE_NOT(14, "Creator Chose Not To Use Archive Warnings"), NONE(16, "No Archive Warnings Apply"),
    VIOLENCE(17, "Graphic Depictions Of Violence"), MCD(18, "Major Character Death"),
    NONCON(19, "Rape/Non-Con"), UNDERAGE(20, "Underage Sex")
}

@Serializable
enum class Category(val id: Int, val label: String) {
    GEN(21, "Gen"), FM(22, "F/M"), MM(23, "M/M"), OTHER(24, "Other"), FF(116, "F/F"), MULTI(2246, "Multi")
}

@Serializable
enum class Completion { ANY, COMPLETE, IN_PROGRESS }

@Serializable
enum class Crossover { ANY, INCLUDE_ONLY, EXCLUDE }

@Serializable
enum class SortColumn(val param: String) {
    BEST_MATCH("_score"), AUTHOR("authors_to_sort_on"), TITLE("title_to_sort_on"), POSTED("created_at"),
    UPDATED("revised_at"), WORDS("word_count"), HITS("hits"), KUDOS("kudos_count"),
    COMMENTS("comments_count"), BOOKMARKS("bookmarks_count")
}

@Serializable
data class SearchQuery(
    val anyField: String = "", val title: String = "", val creators: String = "",
    val fandoms: List<String> = emptyList(), val characters: List<String> = emptyList(),
    val relationships: List<String> = emptyList(), val freeforms: List<String> = emptyList(),
    val excludedTags: List<String> = emptyList(),
    val ratings: Set<Rating> = emptySet(), val warnings: Set<Warning> = emptySet(),
    val categories: Set<Category> = emptySet(),
    val completion: Completion = Completion.ANY, val crossover: Crossover = Crossover.ANY,
    val wordsFrom: Int? = null, val wordsTo: Int? = null,
    val language: String = "",            // AO3 language code e.g. "en"
    val updatedWithin: String = "",       // AO3 revised_at syntax e.g. "< 7 days"
    val sort: SortColumn = SortColumn.UPDATED, val sortDescending: Boolean = true,
)

@Serializable
data class WorkBlurb(
    val workId: Long, val title: String, val authors: List<String>, val fandoms: List<String>,
    val rating: String?, val warnings: List<String>, val categories: List<String>, val complete: Boolean?,
    val relationships: List<String>, val characters: List<String>, val freeforms: List<String>,
    val summaryHtml: String?, val language: String?, val words: Int?,
    val chaptersPosted: Int?, val chaptersTotal: Int?,   // total null when "?"
    val kudos: Int?, val hits: Int?, val bookmarks: Int?, val comments: Int?, val updated: String?,
    /** The tappable authors (orphaned/anonymous ones have none), in byline order. Defaults keep older cached JSON readable. */
    val authorLinks: List<AuthorLink> = emptyList(),
    val series: List<SeriesLink> = emptyList(),
)

/** An author byline link `/users/{user}/pseuds/{pseud}`; [name] is the link text (e.g. "Pseud (user)"). */
@Serializable
data class AuthorLink(val name: String, val user: String, val pseud: String)

/** A series a work belongs to; [part] is its position ("Part N of"), null when not shown. */
@Serializable
data class SeriesLink(val id: Long, val title: String, val part: Int? = null)

data class WorkPage(val works: List<WorkBlurb>, val page: Int, val hasNext: Boolean)

@Serializable
data class ChapterRef(val chapterId: Long?, val number: Int, val title: String)   // chapterId null for single-chapter works

@Serializable
sealed interface Span {
    val start: Int
    val end: Int
    @Serializable @SerialName("bold")
    data class Bold(override val start: Int, override val end: Int) : Span
    @Serializable @SerialName("italic")
    data class Italic(override val start: Int, override val end: Int) : Span
    @Serializable @SerialName("underline")
    data class Underline(override val start: Int, override val end: Int) : Span
    @Serializable @SerialName("strike")
    data class Strike(override val start: Int, override val end: Int) : Span
    @Serializable @SerialName("link")
    data class Link(override val start: Int, override val end: Int, val url: String) : Span
}

@Serializable
sealed interface Block {
    @Serializable @SerialName("text")
    data class Text(val text: String, val spans: List<Span>, val center: Boolean = false) : Block
    @Serializable @SerialName("heading")
    data class Heading(val text: String, val level: Int) : Block
    @Serializable @SerialName("quote")
    data class Quote(val text: String, val spans: List<Span>) : Block
    @Serializable @SerialName("rule")
    data object Rule : Block
}

@Serializable
data class ChapterContent(
    val workId: Long, val ref: ChapterRef, val workTitle: String,
    val authors: List<String>, val beginNotes: List<Block>, val body: List<Block>, val endNotes: List<Block>,
    val summary: List<Block>,   // summary only populated for chapter 1
    val authorLinks: List<AuthorLink> = emptyList(),
    /** From the work meta (any chapter page). */
    val series: List<SeriesLink> = emptyList(),
)
