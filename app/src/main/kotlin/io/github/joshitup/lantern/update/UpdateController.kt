package io.github.joshitup.lantern.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
        _state.value = UpdateState.Failed("Install failed: ${message ?: "unknown error"}")
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
            if (!verify(apk)) {
                _state.value = UpdateState.Failed(UNVERIFIED)
                return
            }
            _state.value = UpdateState.Installing
            markPending(release) // the process dies when the new version replaces this one
            install(apk)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = UpdateState.Failed("Install failed: ${e.message ?: e::class.java.simpleName}")
        } finally {
            apk.delete() // unverified, or already copied into the install session
        }
    }

    private val UpdateState.busy get() = this is UpdateState.Downloading || this is UpdateState.Installing

    private companion object {
        const val UNVERIFIED = "This update couldn't be verified."
        const val DOWNLOAD_FAILED = "Couldn't download the update. Tap to retry."

        /** Only GitHub release assets; OkHttp follows the redirect to GitHub's storage. */
        fun trustedUrl(url: String): Boolean = try {
            val u = url.toHttpUrl()
            u.isHttps && u.host == "github.com"
        } catch (e: IllegalArgumentException) {
            false
        }
    }
}
