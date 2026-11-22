package io.github.joshitup.lantern.ao3.net

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

/** The cause in [FetchResult.Failed] for an unexpected HTTP status (not 2xx, 404, 429 or a challenge). */
class HttpStatusException(val code: Int) : java.io.IOException("HTTP $code")

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
     * fetch lock is not held while a challenge window waits. A 429 is returned as
     * [FetchResult.RateLimited] at once rather than sleeping and retrying.
     *
     * [anonymous] true tags the request with [Anonymous]: the app's network interceptor then sends it
     * without the AO3 session cookies and drops any session cookie the response tries to set.
     *
     * [forceNetwork] true never answers from the HTTP cache: the request reaches AO3 (a signed-in chapter
     * load must, so it is recorded in the owner's AO3 History). Anonymous requests always bypass the cache.
     */
    suspend fun get(
        url: HttpUrl,
        accept: String? = null,
        interactive: Boolean = true,
        anonymous: Boolean = false,
        forceNetwork: Boolean = false,
    ): FetchResult

    /**
     * Form POST (application/x-www-form-urlencoded). On a Cloudflare challenge: solves once (if interactive) and
     * returns [FetchResult.Blocked] WITHOUT resubmitting — the caller re-GETs the form for a fresh token.
     * [accept] and [interactive] work as for [get] (non-interactive: a challenge or 429 returns at once).
     */
    suspend fun post(
        url: HttpUrl,
        fields: List<Pair<String, String>>,
        accept: String? = null,
        interactive: Boolean = true,
    ): FetchResult
}

/** OkHttp request tag type: a request tagged with it is sent without the user's AO3 session. */
object Anonymous
