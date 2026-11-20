package app.ao3reader.account

import app.ao3reader.ao3.net.FetchResult
import app.ao3reader.ao3.net.Fetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl

/**
 * Hands every Ok page body fetched WITH the session to [onOk] (on a background thread; failures ignored),
 * so the account state follows whatever the app loads. Skipped: anonymous GETs (signed out by design),
 * XHR requests ([accept] set: JSON, no page header) and POSTs (Session reads its own responses).
 */
class ObservingFetcher(private val delegate: Fetcher, private val onOk: (String) -> Unit) : Fetcher {
    override suspend fun get(url: HttpUrl, accept: String?, interactive: Boolean, anonymous: Boolean): FetchResult {
        val result = delegate.get(url, accept, interactive, anonymous)
        if (result is FetchResult.Ok && !anonymous && accept == null) {
            withContext(Dispatchers.Default) {
                try { onOk(result.html) } catch (_: Exception) { }
            }
        }
        return result
    }

    override suspend fun post(url: HttpUrl, fields: List<Pair<String, String>>, accept: String?): FetchResult =
        delegate.post(url, fields, accept)
}
