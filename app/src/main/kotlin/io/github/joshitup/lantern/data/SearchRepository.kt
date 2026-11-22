package io.github.joshitup.lantern.data

import io.github.joshitup.lantern.ao3.model.SearchQuery
import io.github.joshitup.lantern.ao3.model.WorkPage
import io.github.joshitup.lantern.ao3.net.FetchResult
import io.github.joshitup.lantern.ao3.net.Fetcher
import io.github.joshitup.lantern.ao3.parse.Ao3Urls
import io.github.joshitup.lantern.ao3.parse.Autocomplete
import io.github.joshitup.lantern.ao3.parse.BlurbParser
import io.github.joshitup.lantern.ao3.search.toUrl
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the search screen needs; lets the ViewModel be tested with a fake. */
interface SearchSource {
    suspend fun search(q: SearchQuery, page: Int): Loaded<WorkPage>
    suspend fun autocomplete(kind: String, term: String): List<String>

    /** Suggestions available without a request (see [SearchRepository.cachedAutocomplete]); null when none. */
    fun cachedAutocomplete(kind: String, term: String): LocalSuggestions? = null
}

/** Suggestions from the cache. [final] means a request could not add anything, so none is needed. */
data class LocalSuggestions(val names: List<String>, val final: Boolean)

class SearchRepository(
    private val fetcher: Fetcher,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SearchSource {
    companion object {
        /** What AO3's own JS sends; the autocomplete controller only responds to JSON. */
        const val AUTOCOMPLETE_ACCEPT = "application/json, text/javascript, */*; q=0.01"
        /** Treat fewer than this many suggestions as the complete match list; conservative (AO3 returns up to ~15–16). */
        const val AUTOCOMPLETE_PAGE_LIMIT = 10
        const val AUTOCOMPLETE_CACHE_SIZE = 100
    }

    /** LRU of successful autocomplete results, keyed by (kind, trimmed lowercase term). */
    private val suggestionCache = object : LinkedHashMap<Pair<String, String>, List<String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String>, List<String>>?) =
            size > AUTOCOMPLETE_CACHE_SIZE
    }

    override suspend fun search(q: SearchQuery, page: Int): Loaded<WorkPage> =
        when (val r = fetcher.get(q.toUrl(page))) {
            is FetchResult.Ok -> Loaded.Ok(withContext(parseDispatcher) { BlurbParser.parsePage(r.html, page) })
            else -> Loaded.Error(r)
        }

    override suspend fun autocomplete(kind: String, term: String): List<String> {
        val t = term.trim()
        if (t.length < 2) return emptyList()
        val key = kind to t.lowercase()
        synchronized(suggestionCache) { suggestionCache[key] }?.let { return it }
        val r = fetcher.get(Ao3Urls.autocomplete(kind, t), accept = AUTOCOMPLETE_ACCEPT) as? FetchResult.Ok ?: return emptyList()
        // Only a real JSON array is cached: an HTML page served as 200 (Cloudflare interstitial,
        // maintenance) would otherwise cache as "no matches" and silence every longer term.
        val names = withContext(parseDispatcher) { Autocomplete.parseOrNull(r.html) } ?: return emptyList()
        synchronized(suggestionCache) { suggestionCache[key] = names }
        return names
    }

    /**
     * An exact cache hit (final), or else the cached result of the longest cached prefix of [term],
     * narrowed to names containing every word of [term]. That is final when the prefix result was
     * complete (under [AUTOCOMPLETE_PAGE_LIMIT] names): longer terms can only match a subset of it.
     */
    override fun cachedAutocomplete(kind: String, term: String): LocalSuggestions? {
        val t = term.trim().lowercase()
        if (t.length < 2) return null
        synchronized(suggestionCache) {
            suggestionCache[kind to t]?.let { return LocalSuggestions(it, final = true) }
            for (len in t.length - 1 downTo 2) {
                val prefix = suggestionCache[kind to t.substring(0, len)] ?: continue
                val words = t.split(' ').filter { it.isNotBlank() }
                val narrowed = prefix.filter { name -> words.all { name.contains(it, ignoreCase = true) } }
                return LocalSuggestions(narrowed, final = prefix.size < AUTOCOMPLETE_PAGE_LIMIT)
            }
        }
        return null
    }
}
