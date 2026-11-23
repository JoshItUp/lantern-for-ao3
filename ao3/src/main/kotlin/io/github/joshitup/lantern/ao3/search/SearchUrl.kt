package io.github.joshitup.lantern.ao3.search

import io.github.joshitup.lantern.ao3.model.Completion
import io.github.joshitup.lantern.ao3.model.Crossover
import io.github.joshitup.lantern.ao3.model.SearchQuery
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

fun SearchQuery.toUrl(page: Int): HttpUrl {
    val b = "https://archiveofourown.org/works/search".toHttpUrl().newBuilder()
    fun add(name: String, value: String) {
        if (value.isNotBlank()) b.addQueryParameter("work_search[$name]", value)
    }
    add("query", anyField)
    add("title", title)
    add("creators", creators)
    add("fandom_names", fandoms.joinToString(","))
    add("character_names", characters.joinToString(","))
    add("relationship_names", relationships.joinToString(","))
    add("freeform_names", freeforms.joinToString(","))
    add("excluded_tag_names", excludedNames().joinToString(","))
    ratings.forEach { b.addQueryParameter("work_search[rating_ids]", it.id.toString()) }
    warnings.forEach { b.addQueryParameter("work_search[archive_warning_ids][]", it.id.toString()) }
    categories.forEach { b.addQueryParameter("work_search[category_ids][]", it.id.toString()) }
    when (completion) {
        Completion.ANY -> {}
        Completion.COMPLETE -> add("complete", "T")
        Completion.IN_PROGRESS -> add("complete", "F")
    }
    when (crossover) {
        Crossover.ANY -> {}
        Crossover.INCLUDE_ONLY -> add("crossover", "T")
        Crossover.EXCLUDE -> add("crossover", "F")
    }
    wordsFrom?.let { add("words_from", it.toString()) }
    wordsTo?.let { add("words_to", it.toString()) }
    add("language_id", language)
    add("revised_at", updatedWithin)
    b.addQueryParameter("page", page.toString())
    add("sort_column", sort.param)
    add("sort_direction", if (sortDescending) "desc" else "asc")
    return b.build()
}
