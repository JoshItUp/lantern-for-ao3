package io.github.joshitup.lantern.net

import io.github.joshitup.lantern.ao3.net.FetchResult
import io.github.joshitup.lantern.ao3.net.Fetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl

/**
 * Builds the real fetcher on the first request, on an IO thread. Creating it touches the WebView
 * stack (user agent, CookieManager), which must not happen on the main thread at app start.
 */
class DeferredFetcher(create: () -> Fetcher) : Fetcher {
    private val delegate = lazy(create)

    private suspend fun delegate(): Fetcher =
        if (delegate.isInitialized()) delegate.value else withContext(Dispatchers.IO) { delegate.value }

    override suspend fun get(url: HttpUrl, accept: String?, interactive: Boolean, anonymous: Boolean, forceNetwork: Boolean): FetchResult =
        delegate().get(url, accept, interactive, anonymous, forceNetwork)

    override suspend fun post(url: HttpUrl, fields: List<Pair<String, String>>, accept: String?, interactive: Boolean): FetchResult =
        delegate().post(url, fields, accept, interactive)
}
