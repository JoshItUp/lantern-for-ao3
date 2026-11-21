package app.ao3reader.data

import app.ao3reader.ao3.net.FetchResult
import app.ao3reader.ao3.parse.Ao3Urls
import app.ao3reader.ao3.parse.WorkListPage
import app.ao3reader.ao3.parse.WorkListParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl

/** What the work-list screen needs (a seam for tests). */
fun interface WorkListSource {
    suspend fun load(url: HttpUrl, page: Int): Loaded<WorkListPage>
}

/** Thrown (inside [FetchResult.Failed]) for a URL that is not an AO3 series or author works list. */
class NotAWorkListException : IllegalArgumentException("not an AO3 work list")

/**
 * A series' or author's works, page by page. While an account acts the page is loaded signed in (it then
 * includes works restricted to signed-in users), else signed out. Never cached.
 */
class WorkListRepository(
    private val acting: () -> Boolean,
    private val authedGet: suspend (HttpUrl) -> FetchResult,
    private val plainGet: suspend (HttpUrl) -> FetchResult,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Each signed-in page, with its list: the subscribe state ([ActionRepository.updateList]). */
    private val signedInPage: ((list: HttpUrl, html: String, pageUrl: HttpUrl) -> Unit)? = null,
) : WorkListSource {
    override suspend fun load(url: HttpUrl, page: Int): Loaded<WorkListPage> {
        val list = Ao3Urls.workList(url) ?: return Loaded.Error(FetchResult.Failed(NotAWorkListException()))
        val target = Ao3Urls.page(list, page)
        val signedIn = acting()
        val r = if (signedIn) authedGet(target) else plainGet(target)
        if (r !is FetchResult.Ok) return Loaded.Error(r)
        return Loaded.Ok(withContext(parseDispatcher) {
            if (signedIn) signedInPage?.let { sink ->
                try { sink(list, r.html, r.finalUrl) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
            }
            WorkListParser.parse(r.html, page)
        })
    }
}
