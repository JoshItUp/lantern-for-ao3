package io.github.joshitup.lantern.update

import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File

/**
 * Runs one update at a time on [scope] (the app scope, so leaving the screen doesn't cancel it):
 * permission, download, verify, persist the pending "What's new", then install. The steps are injected for tests.
 */
class UpdateController(
    private val scope: CoroutineScope,
    private val canInstall: () -> Boolean,
    private val download: suspend (ReleaseInfo, (Float) -> Unit) -> File,
    private val verify: suspend (File) -> Boolean,
    private val markPending: suspend (ReleaseInfo) -> Unit,
    private val install: suspend (File) -> Unit,
    private val installTimeoutMs: Long = INSTALL_TIMEOUT_MS,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** No-op while a download or install runs. After [UpdateState.NeedsPermission], call again once the user returns. */
    fun start(release: ReleaseInfo) {
        val prev = _state.getAndUpdate { if (it.busy) it else UpdateState.Downloading(0f) }
        if (prev.busy) return
        scope.launch { run(release) }
    }

    fun resetIfFailed() {
        _state.update { if (it is UpdateState.Failed) UpdateState.Idle else it }
    }

    /** From [InstallResultReceiver]: the system refused the package or the user cancelled. */
    fun reportInstallFailure(message: String?) {
        disarmWatchdog()
        _state.value = UpdateState.Failed("Install failed: ${message ?: "unknown error"}")
    }

    /** From [InstallResultReceiver] on STATUS_PENDING_USER_ACTION; the receiver's own launch may be silently blocked. */
    fun awaitConfirmation(intent: Intent) {
        _state.value = UpdateState.AwaitingConfirmation(intent)
        armWatchdog()
    }

    /** The UI launched the confirmation itself (on resume). */
    fun confirmationLaunched() {
        _state.update { if (it is UpdateState.AwaitingConfirmation) UpdateState.Installing else it }
        armWatchdog()
    }

    @Volatile private var watchdog: Job? = null

    /** If no callback arrives in time (or the confirmation never shows), fail so the user can retry. */
    private fun armWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(installTimeoutMs)
            _state.update { if (it.installing) UpdateState.Failed(NO_RESPONSE) else it }
        }
    }

    private fun disarmWatchdog() {
        watchdog?.cancel()
        watchdog = null
    }

    private suspend fun run(release: ReleaseInfo) {
        if (!canInstall()) {
            _state.value = UpdateState.NeedsPermission
            return
        }
        if (!trustedUrl(release.apkUrl)) {
            _state.value = UpdateState.Failed(UNVERIFIED)
            return
        }
        val apk = try {
            download(release) { p -> _state.value = UpdateState.Downloading(p) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(DOWNLOAD_FAILED)
            return
        }
        try {
            val ok = try {
                verify(apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (!ok) {
                _state.value = UpdateState.Failed(UNVERIFIED)
                return
            }
            _state.value = UpdateState.Installing
            armWatchdog()
            markPending(release) // the process dies when the new version replaces this one
            install(apk)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            disarmWatchdog()
            _state.value = UpdateState.Failed("Install failed: ${e.message ?: e::class.java.simpleName}")
        } finally {
            apk.delete() // unverified, or already copied into the install session
        }
    }

    /** A stale [UpdateState.AwaitingConfirmation] is not busy: start() may run again from it. */
    private val UpdateState.busy get() = this is UpdateState.Downloading || this is UpdateState.Installing
    private val UpdateState.installing get() = this is UpdateState.Installing || this is UpdateState.AwaitingConfirmation

    companion object {
        const val INSTALL_TIMEOUT_MS = 120_000L
        private const val NO_RESPONSE = "Install failed: no response from the system"
        private const val UNVERIFIED = "This update couldn't be verified."
        private const val DOWNLOAD_FAILED = "Couldn't download the update. Tap to retry."

        /** Only GitHub release assets; OkHttp follows the redirect to GitHub's storage. */
        private fun trustedUrl(url: String): Boolean = try {
            val u = url.toHttpUrl()
            u.isHttps && u.host == "github.com"
        } catch (e: IllegalArgumentException) {
            false
        }
    }
}
