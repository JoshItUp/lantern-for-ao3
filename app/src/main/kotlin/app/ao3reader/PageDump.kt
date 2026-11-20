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

/** Fetcher decorator that hands every Ok body to [dump] off the main thread, ignoring failures. */
class DumpingFetcher(private val delegate: Fetcher, private val dump: PageDump) : Fetcher {
    override suspend fun get(url: HttpUrl, accept: String?): FetchResult {
        val result = delegate.get(url, accept)
        if (result is FetchResult.Ok && dump !== PageDump.None) {
            withContext(Dispatchers.IO) {
                try { dump.dump(result.finalUrl, result.html) } catch (_: Exception) { }
            }
        }
        return result
    }
}
