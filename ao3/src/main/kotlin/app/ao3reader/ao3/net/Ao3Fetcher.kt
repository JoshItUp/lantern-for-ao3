package app.ao3reader.ao3.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val ROBOT_SENTENCE = "our systems think you may be a robot"
/** Cheap pre-check on the raw HTML before parsing it; the full sentence is then matched on the text. */
private const val ROBOT_HINT = "may be a robot"
private const val DEFAULT_RETRY_AFTER_SEC = 60L
/** Longer waits are returned to the caller instead of sleeping while every other request queues behind us. */
private const val MAX_RETRY_WAIT_SEC = 30L

class Ao3Fetcher(
    private val client: OkHttpClient,
    private val userAgent: String,
    private val solver: ChallengeSolver,
    private val minIntervalMs: Long = 1000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : Fetcher {
    private val mutex = Mutex()
    private var lastStart: Long? = null

    private class Raw(val code: Int, val headers: Headers, val body: String, val finalUrl: HttpUrl)

    override suspend fun get(url: HttpUrl, accept: String?, interactive: Boolean): FetchResult = mutex.withLock {
        var rateRetried = false
        var solved = false
        // Bounded: each branch that loops sets a once-only flag first.
        while (true) {
            val raw = try {
                request(url, accept)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                return@withLock FetchResult.Failed(e)
            }

            if (isChallenge(raw.code, raw.headers, raw.body)) {
                // Background fetches never pop the challenge window or hold the lock while it waits.
                if (solved || !interactive) return@withLock FetchResult.Blocked
                solved = true
                val ok = try {
                    solver.solve(url)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                if (!ok) return@withLock FetchResult.Blocked
                // The solver just loaded a page itself: space the retry from that load, not from ours.
                lastStart = clock()
                continue
            }

            when {
                raw.code in 200..299 -> return@withLock FetchResult.Ok(raw.body, raw.finalUrl)
                raw.code == 404 -> return@withLock FetchResult.NotFound
                raw.code == 429 -> {
                    val sec = raw.headers["Retry-After"]?.trim()?.toLongOrNull()
                        ?.takeIf { it >= 0 } ?: DEFAULT_RETRY_AFTER_SEC
                    if (rateRetried || sec > MAX_RETRY_WAIT_SEC) return@withLock FetchResult.RateLimited(sec)
                    rateRetried = true
                    sleep(sec * 1000)
                    continue
                }
                else -> return@withLock FetchResult.Failed(IOException("HTTP ${raw.code}"))
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private suspend fun request(url: HttpUrl, accept: String?): Raw {
        lastStart?.let { last ->
            val wait = minIntervalMs - (clock() - last)
            if (wait > 0) sleep(wait)
        }
        lastStart = clock()
        val req = Request.Builder().url(url).header("User-Agent", userAgent).apply {
            if (accept != null) {
                header("Accept", accept)
                header("X-Requested-With", "XMLHttpRequest")
            }
        }.build()
        return client.newCall(req).await()
    }

    /** Runs the call on OkHttp's dispatcher; cancelling the coroutine cancels the call. */
    private suspend fun Call.await(): Raw = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val raw = try {
                    response.use { Raw(it.code, it.headers, it.body.string(), it.request.url) }
                } catch (e: IOException) {
                    cont.resumeWithException(e)
                    return
                }
                cont.resume(raw)
            }
        })
    }
}

/**
 * True when [html] is AO3's "our systems think you may be a robot" page. The sentence is matched
 * case-insensitively outside work and summary text (`#workskin`, `.userstuff`), so a story that
 * quotes it is never mistaken for a challenge.
 */
fun isRobotPage(html: String): Boolean {
    if (!html.contains(ROBOT_HINT, ignoreCase = true)) return false
    val doc = Jsoup.parse(html)
    doc.select("#workskin, .userstuff").remove()
    return doc.text().contains(ROBOT_SENTENCE, ignoreCase = true)
}

fun isChallenge(code: Int, headers: Headers, body: String): Boolean {
    if (isRobotPage(body)) return true
    if (code != 403 && code != 429 && code != 503) return false
    if (headers["cf-mitigated"].equals("challenge", ignoreCase = true)) return true
    if (headers.values("Set-Cookie").any { it.contains("__cfwaitingroom") }) return true
    return body.contains("cf-chl") || body.contains("Just a moment...")
}
