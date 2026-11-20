package app.ao3reader.net

import app.ao3reader.ao3.net.FetchResult
import app.ao3reader.ao3.net.Fetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl

/**
 * Builds the real fetcher on the first request, on an IO thread. Creating it touches the WebView
 * stack (user agent, CookieManager), which must not happen on the main thread at app start.
 */
class DeferredFetcher(create: () -> Fetcher) : Fetcher {
    private val delegate = lazy(create)

    override suspend fun get(url: HttpUrl, accept: String?): FetchResult {
        val f = if (delegate.isInitialized()) delegate.value else withContext(Dispatchers.IO) { delegate.value }
        return f.get(url, accept)
    }
}
