package app.ao3reader.ui.account

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.ao3reader.AppContainer
import app.ao3reader.account.Session
import app.ao3reader.account.SessionSupersededException
import app.ao3reader.account.SignInResult
import app.ao3reader.ao3.net.FetchResult
import app.ao3reader.ao3.parse.AccountState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the account screen needs from [Session] (a seam for tests). */
interface AccountSource {
    val state: StateFlow<AccountState>
    val needsSignIn: StateFlow<Boolean>
    val credentialsSaved: StateFlow<Boolean>
    suspend fun signIn(username: String, password: String): SignInResult
    suspend fun signOut()
}

/**
 * [onSignedOut] runs after an explicit sign-out (not when a session merely lapses), even when the
 * sign-out threw or was cancelled.
 */
fun Session.asAccountSource(onSignedOut: suspend () -> Unit = {}): AccountSource {
    val session = this
    return object : AccountSource {
        override val state get() = session.state
        override val needsSignIn get() = session.needsSignIn
        override val credentialsSaved get() = session.credentialsSaved
        override suspend fun signIn(username: String, password: String) = session.signIn(username, password)
        override suspend fun signOut() {
            try {
                session.signOut()
            } finally {
                // Even if the logout failed or the caller was cancelled, the account's cached pages go.
                // runCatching: a failure here must not mask signOut's own exception.
                withContext(NonCancellable) { runCatching { onSignedOut() } }
            }
        }
    }
}

/**
 * Runs every step in order, even when one before it throws; afterwards the last exception (if any)
 * propagates, as with nested `finally` blocks.
 */
internal suspend fun runEach(steps: List<suspend () -> Unit>) {
    var error: Throwable? = null
    for (step in steps) {
        try {
            step()
        } catch (e: Throwable) {
            error = e
        }
    }
    error?.let { throw it }
}

@Immutable
data class AccountUiState(
    val signedIn: Boolean = false,
    val username: String? = null,
    /** False while signed in means silent re-login won't work: the screen shows a note. */
    val credentialsSaved: Boolean = true,
    /** A sign-in or sign-out is in flight: inputs disabled, progress shown. */
    val busy: Boolean = false,
    val error: String? = null,
    /** The first Sign out tap arms this for [SIGN_OUT_CONFIRM_MS]; a second tap signs out. */
    val confirmSignOut: Boolean = false,
    /** One-shot: the sign-in worked and the screen should go back. Cleared by [AccountViewModel.closeHandled]. */
    val close: Boolean = false,
)

const val SIGN_OUT_CONFIRM_MS = 3_000L

/** UI copy for a failed sign-in; null when nothing should be shown (Ok, or superseded by a newer call). */
fun signInErrorMessage(result: SignInResult): String? = when (result) {
    SignInResult.Ok -> null
    is SignInResult.Rejected -> result.message.ifBlank { "AO3 didn't accept those details." }
    is SignInResult.Error -> when (val r = result.result) {
        FetchResult.Blocked -> "AO3 is blocking requests right now."
        is FetchResult.RateLimited -> "AO3 asked us to slow down. Try again in ${r.retryAfterSec} s."
        is FetchResult.Failed -> if (r.cause is SessionSupersededException) null else CONNECTION_ERROR
        else -> CONNECTION_ERROR
    }
}

private const val CONNECTION_ERROR = "Couldn't sign in. Check your connection."

/**
 * Sign-in and sign-out for the account screen. The password is never held here beyond the call that
 * uses it: the screen keeps the field text and passes it to [signIn].
 */
class AccountViewModel(private val source: AccountSource) : ViewModel() {
    private data class Local(
        val busy: Boolean = false,
        /**
         * A sign-in call is in flight. Session flips its state to signed in before it saves the
         * credentials, so until the call returns the screen keeps the form (and its spinner): no
         * signed-in view and no false "couldn't be saved" note mid-flight.
         */
        val signingIn: Boolean = false,
        val error: String? = null,
        val confirmSignOut: Boolean = false,
        val close: Boolean = false,
    )

    private val local = MutableStateFlow(Local())
    private var confirmJob: Job? = null

    val state: StateFlow<AccountUiState> =
        combine(source.state, source.credentialsSaved, local, ::merge)
            .stateIn(viewModelScope, SharingStarted.Eagerly, merge(source.state.value, source.credentialsSaved.value, local.value))

    private fun merge(account: AccountState, saved: Boolean, l: Local) = AccountUiState(
        signedIn = account.signedIn && !l.signingIn,
        username = if (l.signingIn) null else account.username,
        credentialsSaved = saved,
        busy = l.busy,
        error = l.error,
        confirmSignOut = l.confirmSignOut,
        close = l.close,
    )

    fun canSubmit(username: String, password: String): Boolean =
        username.isNotBlank() && password.isNotBlank() && !local.value.busy

    fun signIn(username: String, password: String) {
        if (!canSubmit(username, password)) return
        local.update { it.copy(busy = true, signingIn = true, error = null, close = false) }
        viewModelScope.launch {
            val result = try {
                source.signIn(username.trim(), password)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SignInResult.Error(FetchResult.Failed(e))
            }
            val ok = result == SignInResult.Ok
            // Without saved credentials stay here, so the owner sees the note about it.
            val close = ok && source.credentialsSaved.value
            local.update { it.copy(busy = false, signingIn = false, error = signInErrorMessage(result), close = close) }
        }
    }

    fun closeHandled() = local.update { it.copy(close = false) }

    /** First tap arms the confirmation for 3 s; a second tap within that time signs out. */
    fun signOutTap() {
        if (local.value.busy) return
        if (!local.value.confirmSignOut) {
            local.update { it.copy(confirmSignOut = true) }
            confirmJob?.cancel()
            confirmJob = viewModelScope.launch {
                delay(SIGN_OUT_CONFIRM_MS)
                local.update { it.copy(confirmSignOut = false) }
            }
            return
        }
        confirmJob?.cancel()
        local.update { it.copy(busy = true, confirmSignOut = false) }
        viewModelScope.launch {
            try {
                source.signOut()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Session.signOut clears cookies and credentials in its own finally; the AO3 logout is best effort.
            } finally {
                local.value = Local()
            }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { AccountViewModel(container.session.asAccountSource {
                // Each cleanup runs even if one before it throws ([runEach]); if several throw, the last
                // one propagates, and asAccountSource swallows it so sign-out itself never fails.
                runEach(listOf(
                    { container.actionRepo.clear() },
                    { container.libraryRepo.clear() },
                    { withContext(Dispatchers.IO) { container.chapterCache.clear() } },
                    // Signed-in responses may sit in the HTTP cache; skipped if the client was never built.
                    { container.evictHttpCache() },
                ))
            }) }
        }
    }
}
