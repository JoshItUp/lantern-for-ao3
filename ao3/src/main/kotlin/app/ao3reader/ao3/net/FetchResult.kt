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
    /**
     * [accept] non-null marks an XHR-style request: the fetcher sends it as `Accept` together with
     * `X-Requested-With: XMLHttpRequest` (AO3's JSON endpoints answer 406 without them).
     *
     * [interactive] false is for background work (prefetch): a Cloudflare challenge returns
     * [FetchResult.Blocked] at once instead of showing the solver, so nothing pops up unasked and the
     * fetch lock is not held while a challenge window waits.
     */
    suspend fun get(url: HttpUrl, accept: String? = null, interactive: Boolean = true): FetchResult
}
