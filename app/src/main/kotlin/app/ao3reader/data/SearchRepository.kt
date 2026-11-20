package app.ao3reader.data

import app.ao3reader.ao3.model.SearchQuery
import app.ao3reader.ao3.model.WorkPage
import app.ao3reader.ao3.net.FetchResult
import app.ao3reader.ao3.net.Fetcher
import app.ao3reader.ao3.parse.Ao3Urls
import app.ao3reader.ao3.parse.Autocomplete
import app.ao3reader.ao3.parse.BlurbParser
import app.ao3reader.ao3.search.toUrl
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the search screen needs; lets the ViewModel be tested with a fake. */
interface SearchSource {
    suspend fun search(q: SearchQuery, page: Int): Loaded<WorkPage>
    suspend fun autocomplete(kind: String, term: String): List<String>
}

class SearchRepository(
    private val fetcher: Fetcher,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SearchSource {
    companion object {
        /** What AO3's own JS sends; the autocomplete controller only responds to JSON. */
        const val AUTOCOMPLETE_ACCEPT = "application/json, text/javascript, */*; q=0.01"
    }

    override suspend fun search(q: SearchQuery, page: Int): Loaded<WorkPage> =
        when (val r = fetcher.get(q.toUrl(page))) {
            is FetchResult.Ok -> Loaded.Ok(withContext(parseDispatcher) { BlurbParser.parsePage(r.html, page) })
            else -> Loaded.Error(r)
        }

    override suspend fun autocomplete(kind: String, term: String): List<String> {
        val t = term.trim()
        if (t.length < 2) return emptyList()
        val r = fetcher.get(Ao3Urls.autocomplete(kind, t), accept = AUTOCOMPLETE_ACCEPT) as? FetchResult.Ok ?: return emptyList()
        return withContext(parseDispatcher) { Autocomplete.parse(r.html) }
    }
}
