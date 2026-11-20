package app.ao3reader.account

import app.ao3reader.ao3.net.FetchResult
import app.ao3reader.ao3.net.Fetcher
import app.ao3reader.ao3.parse.AccountParser
import app.ao3reader.ao3.parse.AccountState
import app.ao3reader.ao3.parse.Ao3Form
import app.ao3reader.ao3.parse.Ao3Urls
import app.ao3reader.ao3.parse.FormParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import org.jsoup.Jsoup
import java.util.concurrent.atomic.AtomicLong

sealed interface SignInResult {
    data object Ok : SignInResult
    /** AO3 turned the credentials down; [message] is AO3's flash text (never the credentials). */
    data class Rejected(val message: String) : SignInResult
    data class Error(val result: FetchResult) : SignInResult
}

/** Thrown into [FetchResult.Failed] when AO3 answers with something the sign-in flow can't use. */
class SignInFlowException(message: String) : Exception(message)

/**
 * The cause of the [FetchResult.Failed] that [Session.authedPost] returns when the POST came back signed
 * out and the session was renewed. The POST was NOT resent: its authenticity token belonged to the old
 * session. The caller re-GETs the page the form came from (now signed in) and submits the fresh form.
 */
class SessionRenewedException : Exception("session renewed; fetch the form again")

/**
 * The owner's AO3 session. [fetcher] must be the chain WITHOUT the [ObservingFetcher]: Session reads
 * account state from its own responses.
 *
 * Silent re-login: when stored credentials exist and a signed-in page was expected ([authedGet],
 * [authedPost]) but AO3 shows the signed-out header, Session logs in again with the stored
 * credentials, at most once per call; concurrent callers share one attempt.
 * - Rejected credentials are kept, [needsSignIn] turns true and nothing is tried silently until the
 *   owner signs in again (or a signed-in page is seen).
 * - Any other failure (Blocked, RateLimited, network, odd page) pauses silent attempts for
 *   [PAUSE_MS] or until a signed-in page is seen.
 * - [signIn] and [signOut] supersede an in-flight re-login (cancelled; a late result is discarded).
 */
class Session(
    private val fetcher: Fetcher,
    private val store: CredentialStore,
    /** Expires AO3's session cookies. */
    private val cookies: () -> Unit,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** An account was signed in (stored, or signed in this run even if saving failed); gates signed-in observations. */
    @Volatile private var accountKnown = store.username() != null

    private val _state = MutableStateFlow(
        (store.displayName() ?: store.username()).let { AccountState(it != null, it) }
    )
    val state: StateFlow<AccountState> = _state.asStateFlow()

    private val _needsSignIn = MutableStateFlow(false)
    /** True after the stored credentials were rejected by a silent re-login: the UI should prompt. */
    val needsSignIn: StateFlow<Boolean> = _needsSignIn.asStateFlow()

    private val _credentialsSaved = MutableStateFlow(accountKnown)
    /**
     * Whether credentials are stored for silent re-login. False after a [signIn] whose save failed
     * (Keystore error): the session is live, but it won't be renewed silently when it expires.
     */
    val credentialsSaved: StateFlow<Boolean> = _credentialsSaved.asStateFlow()

    private val reloginLock = Mutex()
    /** Guarded by [reloginLock]. */
    private var inFlight: Deferred<Boolean>? = null
    /** Bumped on every successful login, so a request that started before it retries instead of logging in again. */
    private val generation = AtomicLong()
    /** Bumped by [signIn] and [signOut]: a login started under an older epoch is discarded. */
    private val epoch = AtomicLong()
    /** Silent re-login is paused until this clock time after a failure that wasn't a rejection. */
    @Volatile private var pausedUntil = 0L

    suspend fun signIn(username: String, password: String): SignInResult {
        val ep = supersede()
        val result = login(username, password, verify = true, expectedName = username, interactive = true, ep = ep)
        when (result) {
            SignInResult.Ok -> {
                val name = _state.value.username ?: username
                val saved = reloginLock.withLock {
                    if (epoch.get() != ep) return@withLock false
                    withContext(io) { saveCredentials(username, password, name) }
                }
                accountKnown = true
                _credentialsSaved.value = saved
                _needsSignIn.value = false
            }
            is SignInResult.Rejected -> if (epoch.get() == ep) _state.value = SIGNED_OUT
            is SignInResult.Error -> Unit
        }
        return result
    }

    /** Logs out on AO3 when it can (best effort), then always clears the cookies and stored credentials. */
    suspend fun signOut() {
        withContext(NonCancellable) {
            reloginLock.withLock {
                inFlight?.cancelAndJoin()
                inFlight = null
                epoch.incrementAndGet()
                accountKnown = false
                withContext(io) { store.clear() }
            }
        }
        try {
            val page = fetcher.get(Ao3Urls.logout())
            if (page is FetchResult.Ok && AccountParser.state(page.html).signedIn) {
                val form = FormParser.find(page.html, page.finalUrl, "form[action*=/users/logout]")
                if (form != null) {
                    fetcher.post(form.action, form.fields)
                } else {
                    val token = Jsoup.parse(page.html).selectFirst("meta[name=csrf-token]")?.attr("content")
                    if (!token.isNullOrEmpty()) {
                        fetcher.post(Ao3Urls.logout(), listOf("_method" to "delete", "authenticity_token" to token))
                    }
                }
            }
        } finally {
            // After the join above: no re-login can set new session cookies once these are cleared.
            withContext(NonCancellable + io) {
                cookies()
                store.clear()
            }
            _state.value = SIGNED_OUT
            _needsSignIn.value = false
            _credentialsSaved.value = false
            pausedUntil = 0L
        }
    }

    /**
     * Signed-in GET. On a page showing the signed-out header (while credentials are stored) it re-logs in
     * once and retries once. If the re-login fails, the original response is returned and [state] is signed
     * out. [interactive] false (background work) also keeps the re-login from showing the challenge solver.
     */
    suspend fun authedGet(url: HttpUrl, accept: String? = null, interactive: Boolean = true): FetchResult =
        authed(interactive, isPost = false) { fetcher.get(url, accept, interactive) }

    /**
     * Posts [form] with [overrides] applied: every field named in [overrides] is replaced (at the position
     * of its first occurrence), new names are appended.
     *
     * A POST is never resent. If the state is already signed out, the re-login happens before sending.
     * If the response shows signed out, the session is renewed and the result is
     * `Failed(SessionRenewedException)` when that worked: re-GET the form's page and submit again. If the
     * renewal failed, the signed-out response itself is returned.
     */
    suspend fun authedPost(form: Ao3Form, overrides: List<Pair<String, String>>): FetchResult {
        val fields = applyOverrides(form.fields, overrides)
        return authed(interactive = true, isPost = true) { fetcher.post(form.action, fields) }
    }

    /** Updates [state] from any AO3 page the app fetched with the session. Pages without evidence are ignored. */
    fun observe(html: String) {
        val s = AccountParser.observed(header(html)) ?: return
        if (s.signedIn) acceptSignedIn(s) else if (_state.value.signedIn) _state.value = SIGNED_OUT
    }

    private fun acceptSignedIn(s: AccountState) {
        // Without an account (signed out, or never signed in) a page loaded in the meantime can't revive it.
        if (!accountKnown) return
        _state.value = s
        _needsSignIn.value = false
        pausedUntil = 0L
    }

    private suspend fun authed(interactive: Boolean, isPost: Boolean, request: suspend () -> FetchResult): FetchResult {
        var gen = generation.get()
        var relogged = false
        // The state already says signed out (observed elsewhere): log in before the request, not after.
        if (!_state.value.signedIn && canRelogin()) {
            relogged = true
            relogin(gen, interactive)
            gen = generation.get()
        }
        val first = request()
        if (!showsSignedOut(first)) return first
        _state.value = SIGNED_OUT
        if (relogged || !canRelogin() || !relogin(gen, interactive)) return first
        if (isPost) return FetchResult.Failed(SessionRenewedException())
        val second = request()
        // Read the state from the retry, but never log in again in this call.
        if (showsSignedOut(second)) _state.value = SIGNED_OUT
        return second
    }

    /** True for an Ok page with signed-out evidence; a signed-in page updates [state] on the way. */
    private fun showsSignedOut(result: FetchResult): Boolean {
        if (result !is FetchResult.Ok) return false
        val s = AccountParser.observed(header(result.html)) ?: return false
        if (s.signedIn) acceptSignedIn(s)
        return !s.signedIn
    }

    private fun canRelogin(): Boolean =
        !_needsSignIn.value && clock() >= pausedUntil && store.username() != null

    /** Shares one in-flight re-login. True when signed in again (by this attempt or one since [gen]). */
    private suspend fun relogin(gen: Long, interactive: Boolean): Boolean {
        val attempt = reloginLock.withLock {
            if (generation.get() != gen) return true
            if (!canRelogin()) return false
            inFlight?.takeIf { it.isActive } ?: run {
                val ep = epoch.get()
                scope.async { doRelogin(ep, interactive) }
            }.also { inFlight = it }
        }
        return try {
            attempt.await()
        } catch (e: CancellationException) {
            // The attempt was superseded by signIn/signOut; only rethrow if this caller itself was cancelled.
            currentCoroutineContext().ensureActive()
            false
        }
    }

    private suspend fun doRelogin(ep: Long, interactive: Boolean): Boolean = try {
        val creds = withContext(io) { store.load() }
        if (creds == null) {
            false
        } else {
            val expected = withContext(io) { store.displayName() } ?: creds.first
            when (login(creds.first, creds.second, verify = false, expectedName = expected, interactive = interactive, ep = ep)) {
                SignInResult.Ok -> {
                    val name = _state.value.username
                    if (name != null && name != expected) {
                        withContext(io) { runCatching { if (epoch.get() == ep) store.saveDisplayName(name) } }
                    }
                    true
                }
                is SignInResult.Rejected -> {
                    if (epoch.get() == ep) {
                        _state.value = SIGNED_OUT
                        _needsSignIn.value = true
                    }
                    false
                }
                is SignInResult.Error -> {
                    if (epoch.get() == ep) pausedUntil = clock() + PAUSE_MS
                    false
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        if (epoch.get() == ep) pausedUntil = clock() + PAUSE_MS
        false
    }

    /**
     * GET the login page → fill the form → POST. A Cloudflare-blocked POST re-GETs the form once for a fresh
     * token. A login page that is already signed in counts as success only for a silent re-login
     * (![verify]) as [expectedName]; otherwise the session cookies are cleared and the page fetched once
     * more, so the password really is checked.
     */
    private suspend fun login(
        username: String,
        password: String,
        verify: Boolean,
        expectedName: String,
        interactive: Boolean,
        ep: Long,
    ): SignInResult {
        var cleared = false
        var reposted = false
        while (true) {
            val page = fetcher.get(Ao3Urls.login(), interactive = interactive)
            if (page !is FetchResult.Ok) return SignInResult.Error(page)
            val already = AccountParser.state(page.html)
            if (already.signedIn) {
                if (!verify && already.username.equals(expectedName, ignoreCase = true)) return loggedIn(already, ep)
                if (cleared) return SignInResult.Error(FetchResult.Failed(SignInFlowException("still signed in after clearing cookies")))
                cleared = true
                withContext(io) { cookies() }
                continue
            }
            val form = AccountParser.loginForm(page.html, page.finalUrl)
                ?: return SignInResult.Error(FetchResult.Failed(SignInFlowException("login form not found")))
            val posted = fetcher.post(form.action, fillLogin(form.fields, username, password), interactive = interactive)
            if (posted is FetchResult.Blocked && !reposted) {
                reposted = true
                continue
            }
            if (posted !is FetchResult.Ok) return SignInResult.Error(posted)
            val s = AccountParser.state(posted.html)
            if (s.signedIn) return loggedIn(s, ep)
            val flash = AccountParser.flashError(posted.html)
            if (flash != null) return SignInResult.Rejected(flash)
            if (AccountParser.loginForm(posted.html, posted.finalUrl) != null) {
                return SignInResult.Rejected("AO3 didn't accept the user name or password.")
            }
            return SignInResult.Error(FetchResult.Failed(SignInFlowException("unexpected page after login")))
        }
    }

    private fun loggedIn(s: AccountState, ep: Long): SignInResult {
        if (epoch.get() != ep) return SignInResult.Error(FetchResult.Failed(SignInFlowException("superseded")))
        _state.value = s
        pausedUntil = 0L
        generation.incrementAndGet()
        return SignInResult.Ok
    }

    /** Cancels an in-flight re-login and starts a new epoch; returns it. */
    private suspend fun supersede(): Long = withContext(NonCancellable) {
        reloginLock.withLock {
            inFlight?.cancelAndJoin()
            inFlight = null
            epoch.incrementAndGet()
        }
    }

    /** False (and nothing stored) when the Keystore fails: the session stays live without silent re-login. */
    private fun saveCredentials(username: String, password: String, name: String): Boolean = try {
        store.save(username, password)
        store.saveDisplayName(name)
        true
    } catch (_: Exception) {
        runCatching { store.clear() }
        false
    }

    companion object {
        /** How long silent re-login stays paused after a failure that wasn't a rejection. */
        const val PAUSE_MS = 5 * 60_000L

        private val SIGNED_OUT = AccountState(false, null)
        private const val LOGIN = "user[login]"
        private const val PASSWORD = "user[password]"
        private const val REMEMBER = "user[remember_me]"

        /** The greeting and login link live in the header: parse only up to the main region (chapter pages are big). */
        private fun header(html: String): String {
            val main = html.indexOf("id=\"main\"")
            return if (main > 0) html.substring(0, main) else html
        }

        /** Owner's login and password in place; the hidden remember_me "0" kept and a "1" after it, so Rails takes "1". */
        private fun fillLogin(fields: List<Pair<String, String>>, username: String, password: String): List<Pair<String, String>> {
            val out = ArrayList<Pair<String, String>>(fields.size + 3)
            var login = false
            var pass = false
            for ((k, v) in fields) when (k) {
                LOGIN -> if (!login) { out += k to username; login = true }
                PASSWORD -> if (!pass) { out += k to password; pass = true }
                REMEMBER -> if (v == "0") out += k to v
                else -> out += k to v
            }
            if (!login) out += LOGIN to username
            if (!pass) out += PASSWORD to password
            val last = out.indexOfLast { it.first == REMEMBER }
            if (last >= 0) out.add(last + 1, REMEMBER to "1") else out.add(REMEMBER to "1")
            return out
        }

        private fun applyOverrides(fields: List<Pair<String, String>>, overrides: List<Pair<String, String>>): List<Pair<String, String>> {
            val names = overrides.map { it.first }.toSet()
            val out = ArrayList<Pair<String, String>>(fields.size + overrides.size)
            val placed = HashSet<String>()
            for ((k, v) in fields) {
                if (k !in names) {
                    out += k to v
                } else if (placed.add(k)) {
                    out += overrides.filter { it.first == k }
                }
            }
            for (o in overrides) if (o.first !in placed) out += o
            return out
        }
    }
}
