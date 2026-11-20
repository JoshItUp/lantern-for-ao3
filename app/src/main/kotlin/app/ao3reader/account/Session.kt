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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import org.jsoup.Jsoup

sealed interface SignInResult {
    data object Ok : SignInResult
    /** AO3 turned the credentials down; [message] is AO3's flash text (never the credentials). */
    data class Rejected(val message: String) : SignInResult
    data class Error(val result: FetchResult) : SignInResult
}

/** Thrown into [FetchResult.Failed] when AO3 answers with something the sign-in flow can't use. */
class SignInFlowException(message: String) : Exception(message)

/**
 * The owner's AO3 session. [fetcher] must be the chain WITHOUT the [ObservingFetcher]: Session reads
 * account state from its own responses.
 *
 * Silent re-login: when stored credentials exist and a signed-in page was expected ([authedGet],
 * [authedPost]) but AO3 shows the signed-out header, Session logs in again with the stored
 * credentials (once per call; concurrent callers share one attempt) and retries the request once.
 * If AO3 rejects the stored credentials, they are kept but [needsSignIn] turns true and no further
 * silent attempt is made until the owner signs in again.
 */
class Session(
    private val fetcher: Fetcher,
    private val store: CredentialStore,
    /** Expires AO3's session cookies. */
    private val cookies: () -> Unit,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow(store.username().let { AccountState(it != null, it) })
    val state: StateFlow<AccountState> = _state.asStateFlow()

    private val _needsSignIn = MutableStateFlow(false)
    /** True after the stored credentials were rejected by a silent re-login: the UI should prompt. */
    val needsSignIn: StateFlow<Boolean> = _needsSignIn.asStateFlow()

    private val reloginLock = Mutex()
    /** Guarded by [reloginLock]. */
    private var inFlight: Deferred<Boolean>? = null
    /** Bumped on every successful login, so a request that started before it retries instead of logging in again. */
    @Volatile private var generation = 0L

    suspend fun signIn(username: String, password: String): SignInResult {
        val result = login(username, password)
        when (result) {
            SignInResult.Ok -> {
                withContext(io) { store.save(username, password) }
                _needsSignIn.value = false
            }
            is SignInResult.Rejected -> _state.value = SIGNED_OUT
            is SignInResult.Error -> Unit
        }
        return result
    }

    /** Logs out on AO3 when it can (best effort), then always clears the cookies and stored credentials. */
    suspend fun signOut() {
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
            withContext(NonCancellable + io) {
                cookies()
                store.clear()
            }
            _state.value = SIGNED_OUT
            _needsSignIn.value = false
        }
    }

    /**
     * Signed-in GET. On a page showing the signed-out header (while credentials are stored) it re-logs in
     * once and retries once. If the re-login fails, the original response is returned and [state] is signed out.
     */
    suspend fun authedGet(url: HttpUrl, accept: String? = null): FetchResult =
        authed { fetcher.get(url, accept) }

    /**
     * Posts [form] with [overrides] applied: every field named in [overrides] is replaced (at the position
     * of its first occurrence), new names are appended. Same re-login rule as [authedGet]; the retry
     * resends the same fields, so a stale token is the caller's to refresh.
     */
    suspend fun authedPost(form: Ao3Form, overrides: List<Pair<String, String>>): FetchResult {
        val fields = applyOverrides(form.fields, overrides)
        return authed { fetcher.post(form.action, fields) }
    }

    /** Updates [state] from any AO3 page the app fetched with the session. Non-AO3 bodies (JSON) are ignored. */
    fun observe(html: String) {
        if (!isAo3Page(html)) return
        val s = AccountParser.state(header(html))
        if (s.signedIn) {
            _state.value = s
            _needsSignIn.value = false
        } else if (_state.value.signedIn) {
            _state.value = SIGNED_OUT
        }
    }

    private suspend fun authed(request: suspend () -> FetchResult): FetchResult {
        var gen = generation
        var relogged = false
        // The state already says signed out (observed elsewhere): log in before the request, not after.
        if (!_state.value.signedIn && canRelogin()) {
            relogged = true
            relogin(gen)
            gen = generation
        }
        val first = request()
        if (!showsSignedOut(first)) return first
        _state.value = SIGNED_OUT
        if (relogged || !canRelogin() || !relogin(gen)) return first
        val second = request()
        // Read the state from the retry, but never log in again in this call.
        if (showsSignedOut(second)) _state.value = SIGNED_OUT
        return second
    }

    /** True for an Ok AO3 page with the signed-out header; a signed-in page updates [state] on the way. */
    private fun showsSignedOut(result: FetchResult): Boolean {
        if (result !is FetchResult.Ok || !isAo3Page(result.html)) return false
        val s = AccountParser.state(header(result.html))
        if (s.signedIn) {
            _state.value = s
            _needsSignIn.value = false
        }
        return !s.signedIn
    }

    private fun canRelogin(): Boolean = !_needsSignIn.value && store.username() != null

    /** Shares one in-flight re-login. True when signed in again (by this attempt or one since [gen]). */
    private suspend fun relogin(gen: Long): Boolean {
        val attempt = reloginLock.withLock {
            if (generation != gen) return true
            if (_needsSignIn.value) return false
            inFlight?.takeIf { it.isActive } ?: scope.async { doRelogin() }.also { inFlight = it }
        }
        return attempt.await()
    }

    private suspend fun doRelogin(): Boolean = try {
        val creds = withContext(io) { store.load() }
        if (creds == null) {
            _state.value = SIGNED_OUT
            false
        } else {
            when (login(creds.first, creds.second)) {
                SignInResult.Ok -> true
                is SignInResult.Rejected -> {
                    _state.value = SIGNED_OUT
                    _needsSignIn.value = true
                    false
                }
                is SignInResult.Error -> false
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    /**
     * GET the login page → fill the form → POST. A Cloudflare-blocked POST re-GETs the form once for a fresh
     * token; a page already signed in as someone else is cleared and fetched once more.
     */
    private suspend fun login(username: String, password: String): SignInResult {
        var retried = false
        while (true) {
            val page = fetcher.get(Ao3Urls.login())
            if (page !is FetchResult.Ok) return SignInResult.Error(page)
            val already = AccountParser.state(page.html)
            if (already.signedIn) {
                if (already.username.equals(username, ignoreCase = true)) return loggedIn(already)
                if (retried) return SignInResult.Error(FetchResult.Failed(SignInFlowException("signed in as another account")))
                retried = true
                withContext(io) { cookies() }
                continue
            }
            val form = AccountParser.loginForm(page.html, page.finalUrl)
                ?: return SignInResult.Error(FetchResult.Failed(SignInFlowException("login form not found")))
            val posted = fetcher.post(form.action, fillLogin(form.fields, username, password))
            if (posted is FetchResult.Blocked && !retried) {
                retried = true
                continue
            }
            if (posted !is FetchResult.Ok) return SignInResult.Error(posted)
            val s = AccountParser.state(posted.html)
            if (s.signedIn) return loggedIn(s)
            val flash = AccountParser.flashError(posted.html)
            if (flash != null) return SignInResult.Rejected(flash)
            if (AccountParser.loginForm(posted.html, posted.finalUrl) != null) {
                return SignInResult.Rejected("AO3 didn't accept the user name or password.")
            }
            return SignInResult.Error(FetchResult.Failed(SignInFlowException("unexpected page after login")))
        }
    }

    private fun loggedIn(s: AccountState): SignInResult {
        _state.value = s
        generation++
        return SignInResult.Ok
    }

    private companion object {
        val SIGNED_OUT = AccountState(false, null)
        const val LOGIN = "user[login]"
        const val PASSWORD = "user[password]"
        const val REMEMBER = "user[remember_me]"

        /** Pages from AO3 carry the site header; JSON and fragments don't and say nothing about the session. */
        fun isAo3Page(html: String): Boolean = html.contains("id=\"header\"")

        /** The greeting lives in the header: parse only up to the main region (chapter pages are big). */
        fun header(html: String): String {
            val main = html.indexOf("id=\"main\"")
            return if (main > 0) html.substring(0, main) else html
        }

        /** Owner's login and password in place; the hidden remember_me "0" kept and a "1" after it, so Rails takes "1". */
        fun fillLogin(fields: List<Pair<String, String>>, username: String, password: String): List<Pair<String, String>> {
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

        fun applyOverrides(fields: List<Pair<String, String>>, overrides: List<Pair<String, String>>): List<Pair<String, String>> {
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
