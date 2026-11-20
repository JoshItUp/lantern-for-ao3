package app.ao3reader.net

import android.content.Context
import android.content.Intent
import app.ao3reader.ao3.net.ChallengeSolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl

class WebViewChallengeSolver(
    context: Context,
    private val userAgent: String,
    private val timeoutMs: Long = 60_000,
) : ChallengeSolver {
    private val appContext = context.applicationContext
    private val mutex = Mutex()

    override suspend fun solve(url: HttpUrl): Boolean = mutex.withLock {
        val deferred = CompletableDeferred<Boolean>()
        pending = deferred
        try {
            appContext.startActivity(
                Intent(appContext, ChallengeActivity::class.java)
                    .putExtra(ChallengeActivity.EXTRA_URL, url.toString())
                    .putExtra(ChallengeActivity.EXTRA_UA, userAgent)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
        } catch (e: RuntimeException) {
            false
        } finally {
            if (pending === deferred) pending = null
            deferred.complete(false)
            ChallengeActivity.requestFinish()
        }
    }

    companion object {
        @Volatile
        internal var pending: CompletableDeferred<Boolean>? = null

        internal fun complete(result: Boolean) {
            pending?.complete(result)
        }
    }
}
