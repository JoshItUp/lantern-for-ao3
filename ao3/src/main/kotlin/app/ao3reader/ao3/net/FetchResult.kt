package app.ao3reader.ao3.net

import okhttp3.HttpUrl

sealed interface FetchResult {
    data class Ok(val html: String, val finalUrl: HttpUrl) : FetchResult
    data object NotFound : FetchResult
    /** Cloudflare challenge persisted after one solve attempt (or solver declined). */
    data object Blocked : FetchResult
    /** Second 429 in a row. */
    data class RateLimited(val retryAfterSec: Long) : FetchResult
    /** IO error or non-challenge 5xx / unexpected status. */
    data class Failed(val cause: Throwable?) : FetchResult
}

/** Implemented in :app with a WebView. */
fun interface ChallengeSolver {
    suspend fun solve(url: HttpUrl): Boolean
}

interface Fetcher {
    suspend fun get(url: HttpUrl): FetchResult
}
