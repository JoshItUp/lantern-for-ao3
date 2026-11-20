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

class SearchRepository(
    private val fetcher: Fetcher,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun search(q: SearchQuery, page: Int): Loaded<WorkPage> =
        when (val r = fetcher.get(q.toUrl(page))) {
            is FetchResult.Ok -> Loaded.Ok(withContext(parseDispatcher) { BlurbParser.parsePage(r.html, page) })
            else -> Loaded.Error(r)
        }

    suspend fun autocomplete(kind: String, term: String): List<String> {
        val t = term.trim()
        if (t.length < 2) return emptyList()
        val r = fetcher.get(Ao3Urls.autocomplete(kind, t)) as? FetchResult.Ok ?: return emptyList()
        return withContext(parseDispatcher) { Autocomplete.parse(r.html) }
    }
}
