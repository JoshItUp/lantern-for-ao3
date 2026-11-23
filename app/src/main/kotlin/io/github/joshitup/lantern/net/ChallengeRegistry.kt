package io.github.joshitup.lantern.net

import kotlinx.coroutines.CompletableDeferred

/** Tracks the single in-flight challenge request; stale tokens are ignored. */
internal object ChallengeRegistry {
    private val lock = Any()
    private var token = 0L
    private var pending: CompletableDeferred<Boolean>? = null

    fun begin(): Pair<Long, CompletableDeferred<Boolean>> = synchronized(lock) {
        val d = CompletableDeferred<Boolean>()
        token += 1
        pending = d
        token to d
    }

    fun isCurrent(t: Long): Boolean = synchronized(lock) { pending != null && t == token }

    fun complete(t: Long, result: Boolean) {
        val d = synchronized(lock) { if (t == token) pending else null }
        d?.complete(result)
    }

    fun end(t: Long) {
        synchronized(lock) { if (t == token) pending = null }
    }
}
