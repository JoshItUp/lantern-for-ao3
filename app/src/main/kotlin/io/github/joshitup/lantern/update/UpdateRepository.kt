package io.github.joshitup.lantern.update

import io.github.joshitup.lantern.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import java.io.IOException

/** Update check policy, the dismissed strip and the one-time "What's new" after an upgrade. */
class UpdateRepository(
    private val source: ReleaseSource,
    private val settings: SettingsStore,
    private val installedCode: Int,
    private val clock: () -> Long,
    scope: CoroutineScope,
) {
    private val _available = MutableStateFlow<ReleaseInfo?>(null)

    /** A release newer than the installed one, whether or not the user dismissed it. */
    val available: StateFlow<ReleaseInfo?> = _available

    /** [available] and not dismissed (a dismissal covers that version and older). */
    val showStrip: StateFlow<Boolean> = combine(_available, settings.updateDismissedCode) { rel, dismissed ->
        rel != null && rel.version.code > dismissed
    }.stateIn(scope, SharingStarted.Eagerly, false)

    /** At most once per 24 hours; the attempt is recorded even when it fails, so a flaky network is not retried at once. */
    suspend fun maybeCheck() {
        val last = settings.updateLastCheck.first()
        if (last != null && clock() - last < CHECK_INTERVAL_MS) return
        try {
            checkNow()
        } finally {
            settings.setUpdateLastCheck(clock())
        }
    }

    suspend fun checkNow(): CheckResult {
        val latest = try {
            source.latest()
        } catch (e: IOException) {
            return CheckResult.Failed("Couldn't check for updates")
        }
        if (latest == null || latest.version.code <= installedCode) return CheckResult.UpToDate
        _available.value = latest
        return CheckResult.Available(latest)
    }

    suspend fun dismiss() {
        val rel = _available.value ?: return
        settings.setUpdateDismissedCode(rel.version.code)
    }

    /** Saved before the install is committed: the process dies when the new version replaces this one. */
    suspend fun markPendingWhatsNew(release: ReleaseInfo) {
        settings.setUpdatePendingNotes(json.encodeToString(ReleaseInfo.serializer(), release))
    }

    /** Notes for the version just installed, once; null on a fresh install, an unchanged version or when none can be found. */
    suspend fun consumeWhatsNew(): ReleaseInfo? {
        val lastSeen = settings.updateLastSeenCode.first()
        if (lastSeen == null) {
            settings.setUpdateLastSeenCode(installedCode)
            return null
        }
        if (installedCode <= lastSeen) return null
        val pending = settings.updatePendingNotes.first()?.let {
            try {
                json.decodeFromString(ReleaseInfo.serializer(), it)
            } catch (e: IllegalArgumentException) {
                null
            }
        }
        val notes = if (pending != null && pending.version.code == installedCode) {
            pending
        } else {
            try {
                source.byTag("v${versionName(installedCode)}")
            } catch (e: IOException) {
                return null // offline: keep lastSeen so the next launch tries again
            }
        }
        settings.setUpdatePendingNotes(null)
        settings.setUpdateLastSeenCode(installedCode)
        return notes
    }

    private fun versionName(code: Int) = ReleaseVersion(code / 10000, code / 100 % 100, code % 100)

    private companion object {
        const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
        val json = Json { ignoreUnknownKeys = true }
    }
}
