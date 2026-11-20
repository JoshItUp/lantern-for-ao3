package app.ao3reader.ao3.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

private const val ROBOT_MARKER = "our systems think you may be a robot"
private const val DEFAULT_RETRY_AFTER_SEC = 60L

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

    override suspend fun get(url: HttpUrl): FetchResult = mutex.withLock {
        var rateRetried = false
        var solved = false
        // Bounded: each branch that loops sets a once-only flag first.
        while (true) {
            val raw = try {
                request(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                return@withLock FetchResult.Failed(e)
            }

            if (isChallenge(raw.code, raw.headers, raw.body)) {
                if (solved) return@withLock FetchResult.Blocked
                solved = true
                val ok = try {
                    solver.solve(url)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                if (!ok) return@withLock FetchResult.Blocked
                continue
            }

            when {
                raw.code in 200..299 -> return@withLock FetchResult.Ok(raw.body, raw.finalUrl)
                raw.code == 404 -> return@withLock FetchResult.NotFound
                raw.code == 429 -> {
                    val sec = raw.headers["Retry-After"]?.trim()?.toLongOrNull()
                        ?.takeIf { it >= 0 } ?: DEFAULT_RETRY_AFTER_SEC
                    if (rateRetried) return@withLock FetchResult.RateLimited(sec)
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

    private suspend fun request(url: HttpUrl): Raw {
        lastStart?.let { last ->
            val wait = minIntervalMs - (clock() - last)
            if (wait > 0) sleep(wait)
        }
        lastStart = clock()
        val req = Request.Builder().url(url).header("User-Agent", userAgent).build()
        return withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { resp ->
                Raw(resp.code, resp.headers, resp.body.string(), resp.request.url)
            }
        }
    }
}

fun isChallenge(code: Int, headers: Headers, body: String): Boolean {
    if (body.contains(ROBOT_MARKER)) return true
    if (code != 403 && code != 429 && code != 503) return false
    if (headers["cf-mitigated"].equals("challenge", ignoreCase = true)) return true
    if (headers.values("Set-Cookie").any { it.contains("__cfwaitingroom") }) return true
    return body.contains("cf-chl") || body.contains("Just a moment...")
}
