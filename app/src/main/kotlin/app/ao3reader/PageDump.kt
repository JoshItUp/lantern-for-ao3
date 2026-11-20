package app.ao3reader

import app.ao3reader.ao3.net.FetchResult
import app.ao3reader.ao3.net.Fetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl

/** Receives successful page bodies; the release implementation does nothing. */
interface PageDump {
    fun dump(url: HttpUrl, html: String)

    object None : PageDump {
        override fun dump(url: HttpUrl, html: String) = Unit
    }
}

/**
 * Fetcher decorator that hands Ok GET bodies to [dump] off the main thread, ignoring failures. POSTs
 * and anything on the login page (requested or redirected to) are never written: they can hold the
 * user's credentials, tokens or account details.
 */
class DumpingFetcher(private val delegate: Fetcher, private val dump: PageDump) : Fetcher {
    override suspend fun get(url: HttpUrl, accept: String?, interactive: Boolean, anonymous: Boolean): FetchResult {
        val result = delegate.get(url, accept, interactive, anonymous)
        if (result is FetchResult.Ok && dump !== PageDump.None && !isLogin(url) && !isLogin(result.finalUrl)) {
            withContext(Dispatchers.IO) {
                try { dump.dump(result.finalUrl, result.html) } catch (_: Exception) { }
            }
        }
        return result
    }

    override suspend fun post(url: HttpUrl, fields: List<Pair<String, String>>, accept: String?): FetchResult =
        delegate.post(url, fields, accept)

    private fun isLogin(url: HttpUrl): Boolean = url.encodedPath.startsWith("/users/login")
}
