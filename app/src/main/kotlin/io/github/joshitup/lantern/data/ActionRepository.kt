package io.github.joshitup.lantern.data

import io.github.joshitup.lantern.account.Session
import io.github.joshitup.lantern.account.SessionRenewedException
import io.github.joshitup.lantern.ao3.net.FetchResult
import io.github.joshitup.lantern.ao3.net.HttpStatusException
import io.github.joshitup.lantern.ao3.parse.AccountParser
import io.github.joshitup.lantern.ao3.parse.Ao3Form
import io.github.joshitup.lantern.ao3.parse.Ao3Urls
import io.github.joshitup.lantern.ao3.parse.BookmarkInput
import io.github.joshitup.lantern.ao3.parse.ListActions
import io.github.joshitup.lantern.ao3.parse.WorkActions
import io.github.joshitup.lantern.ao3.parse.WorkActionsParser
import io.github.joshitup.lantern.ao3.parse.last
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/** An action AO3 didn't take: the form is missing, or AO3 refused it twice ([message] may be AO3's text). */
class ActionFailedException(message: String) : Exception(message)

/**
 * AO3 answered the POST with a 2xx, but neither the answer nor a fresh GET of the page shows the action took
 * effect. It may or may not have: the UI says so ("may not have posted, check the work").
 */
class ActionUnconfirmedException(message: String) : Exception(message)

/**
 * The POST was sent but failed on the way back ([result]: a network error, timeout or 5xx): AO3 may have
 * taken the action. Not retried; the item's forms are marked stale so the next action reads AO3's state first.
 */
class ActionMayHaveTakenEffectException(val result: FetchResult) : Exception("the request may have reached AO3")

/**
 * The owner's actions on works (kudos, bookmark, mark for later, subscribe, comment) and on series and
 * authors (subscribe), posted through [Session.authedPost] with the forms of the last signed-in page seen.
 *
 * Retry rule, per call: submit once. On a 422, a flash error, `Blocked` or a renewed session, re-GET the
 * page the form came from, check whether the action already took effect (then nothing is posted again),
 * else submit the fresh form once more. Then give up. Never loops.
 *
 * Success is confirmed, never assumed from a 2xx: the answer must be the item's own page showing the action
 * (or a recognised success notice), else a fresh GET must show it; otherwise [ActionUnconfirmedException].
 * Any failure after a POST was sent marks the item stale. POSTs and their bookkeeping are not cancellable.
 *
 * State is per account: it is cleared whenever [Session.actingAccount] changes. Form fields and comment text
 * are never logged.
 */
class ActionRepository(private val session: Session, scope: CoroutineScope? = null) : WorkActionSource, ListActionSource {
    private val works = Book<Long, WorkActions>()
    private val lists = Book<String, ListActions>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    /** Works whose kudos were confirmed for the current account: a re-GET with a truncated kudos list doesn't revert it. */
    private val kudosed = ConcurrentHashMap.newKeySet<Long>()
    @Volatile private var account: String? = session.actingAccount.value?.lowercase()

    /** Per work id: the latest known actions, pending toggles applied. */
    override val state: StateFlow<Map<Long, WorkActions>> = works.flow
    /** Per list key ([listKey]): the latest known, pending toggles applied. */
    override val listState: StateFlow<Map<String, ListActions>> = lists.flow
    override val actingAccount: StateFlow<String?> get() = session.actingAccount

    init {
        scope?.launch { session.actingAccount.collect { syncAccount() } }
    }

    /** From any signed-in page of [workId]; [pageUrl] is where its forms are fetched again. Other pages are ignored. */
    fun update(workId: Long, html: String, pageUrl: HttpUrl) {
        syncAccount()
        val user = ownUser(html) ?: return
        val a = WorkActionsParser.parse(html, pageUrl)
        if (WorkActionsParser.workId(a)?.let { it != workId } == true) return
        putWork(workId, a, pageUrl, user)
    }

    /** From a signed-in series or user page of the list at [listUrl]; pages without the subscribe form are ignored. */
    fun updateList(listUrl: HttpUrl, html: String, pageUrl: HttpUrl) {
        syncAccount()
        val key = listKey(listUrl) ?: return
        val user = ownUser(html) ?: return
        val l = WorkActionsParser.parseList(html, pageUrl)
        if (l.subscribe == null) return
        asAccount(user) { lists.put(key, l, pageUrl) }
    }

    /** The owner's bookmark on [workId] as the known edit form holds it; null when not bookmarked or no edit form is known. */
    override fun existingBookmark(workId: Long): BookmarkInput? {
        syncAccount()
        val a = works.get(workId)?.value ?: return null
        val f = a.bookmarkForm?.takeIf { a.bookmarked && isEditForm(it) } ?: return null
        return WorkActionsParser.bookmarkInput(f)
    }

    /** Forgets everything (sign-out, account change). */
    fun clear() {
        works.clear()
        lists.clear()
        kudosed.clear()
    }

    /** GETs the work page (the last one seen, else the work) and updates [state]. */
    override suspend fun refresh(workId: Long): Loaded<WorkActions> {
        syncAccount()
        if (session.actingAccount.value == null) return notSignedIn()
        return fetchWork(workId, works.get(workId)?.pageUrl ?: Ao3Urls.chapter(workId, null))
    }

    /** GETs the subscribe page of the list at [listUrl] and updates [listState]. */
    override suspend fun refreshList(listUrl: HttpUrl): Loaded<ListActions> {
        syncAccount()
        val key = listKey(listUrl) ?: return notAList()
        if (session.actingAccount.value == null) return notSignedIn()
        return fetchList(key, Ao3Urls.subscribePage(listUrl)!!)
    }

    override suspend fun kudos(workId: Long): Loaded<Unit> {
        syncAccount()
        if (session.actingAccount.value == null) return notSignedIn()
        val acct = account
        return lock("kudos:$workId").withLock {
            val known = currentWork(workId).let { it.ok() ?: return@withLock it as Loaded.Error }
            if (known.value.kudosGiven) return@withLock Loaded.Ok(Unit)
            val r = perform(known.value, workSpec(
                workId, known.pageUrl,
                form = { it.kudos },
                done = { it.kudosGiven },
                confirm = { it.kudosGiven },
                notice = { html -> WorkActionsParser.kudosAlreadyLeft(html) || WorkActionsParser.successNotice(html, KUDOS_THANKS) },
                trust = { it.copy(kudosGiven = true) },
            ))
            // Only for the account that sent it: a sign-in as someone else meanwhile must not inherit it.
            if (r is Loaded.Ok) asAccount(acct) {
                kudosed += workId
                works.modify(workId) { it.copy(kudosGiven = true) }
            }
            r
        }
    }

    /**
     * Mark for later or mark as read. A call while one for the same work is in flight sends nothing and
     * returns `Ok(pending value)`: that is NOT a confirmed result, only what the first call is trying to do.
     */
    override suspend fun toggleMarkForLater(workId: Long): Loaded<Boolean> =
        toggleWork(workId, "mfl", { it.markedForLater }, { a, v -> a.copy(markedForLater = v) }) { it.markForLater }

    /** Subscribe or unsubscribe; a call during one in flight returns `Ok(pending value)`, NOT a confirmed result. */
    override suspend fun toggleSubscribe(workId: Long): Loaded<Boolean> =
        toggleWork(workId, "sub", { it.subscribed }, { a, v -> a.copy(subscribed = v) }) { it.subscribe }

    /**
     * Subscribe to or unsubscribe from a series or an author; [listUrl] is any in-app work list URL
     * ([Ao3Urls.workList]). A call during one in flight returns `Ok(pending value)`, NOT a confirmed result.
     */
    override suspend fun toggleListSubscribe(listUrl: HttpUrl): Loaded<Boolean> {
        syncAccount()
        val key = listKey(listUrl) ?: return notAList()
        val page = Ao3Urls.subscribePage(listUrl)!!
        if (session.actingAccount.value == null) return notSignedIn()
        val m = lock("list:$key")
        if (!m.tryLock()) return Loaded.Ok(lists.flow.value[key]?.subscribed ?: false)
        try {
            val known = lists.get(key)?.takeIf { !it.stale }?.let { Loaded.Ok(it.value) } ?: fetchList(key, page)
            val start = known.ok() ?: return known as Loaded.Error
            val target = !start.subscribed
            val pageUrl = lists.get(key)?.pageUrl ?: page
            val acct = account
            lists.overlay(key, "sub") { it.copy(subscribed = target) }
            try {
                val r = perform(start, Spec(
                    fetch = { fetchList(key, pageUrl) },
                    form = { it.subscribe },
                    done = { it.subscribed == target },
                    confirm = { it.subscribed == target },
                    ownPage = { resp ->
                        val user = ownUser(resp.html)
                        if (user == null) null
                        else WorkActionsParser.parseList(resp.html, resp.finalUrl)
                            .takeIf { it.subscribe != null && sameSubscribable(it.subscribe!!, start.subscribe) }
                            ?.takeIf { l -> asAccount(user) { lists.put(key, l, pageUrl) } }
                    },
                    trust = { asAccount(acct) { lists.markStale(key) { it.copy(subscribed = target) } } },
                    stale = { asAccount(acct) { lists.markStale(key) { it } } },
                ))
                return when (r) {
                    is Loaded.Ok -> Loaded.Ok(target)
                    is Loaded.Error -> r
                }
            } finally {
                lists.clearOverlay(key, "sub")
            }
        } finally {
            m.unlock()
        }
    }

    /**
     * Creates the bookmark, or edits the existing one (fetching its edit page when only the link is known).
     * A bookmark found to exist already is edited with [input]: it is never reported saved without it.
     */
    override suspend fun saveBookmark(workId: Long, input: BookmarkInput): Loaded<Unit> {
        syncAccount()
        if (session.actingAccount.value == null) return notSignedIn()
        return lock("bookmark:$workId").withLock {
            val known = currentWork(workId).let { it.ok() ?: return@withLock it as Loaded.Error }
            perform(known.value, workSpec(
                workId, known.pageUrl,
                form = { a -> bookmarkFormFor(a) },
                // Never skip posting: an existing bookmark gets the owner's input through its (idempotent) edit form.
                done = { false },
                confirm = { a -> a.bookmarked && (a.bookmarkForm?.takeIf(::isEditForm)?.let { matches(WorkActionsParser.bookmarkInput(it), input) } ?: true) },
                overrides = bookmarkOverrides(input),
                trust = { it.copy(bookmarked = true) },
            ))
        }
    }

    /**
     * Posts [text] as-is to [chapterId]'s comment form (the known page's when null). Blank text sends nothing.
     * Confirmed by the answer being the work's page or AO3's "Comment created" notice; otherwise
     * [ActionUnconfirmedException]. A failure after sending is [ActionMayHaveTakenEffectException].
     */
    override suspend fun comment(workId: Long, chapterId: Long?, text: String): Loaded<Unit> {
        if (text.isBlank()) return Loaded.Error(FetchResult.Failed(IllegalArgumentException("comment is blank")))
        syncAccount()
        if (session.actingAccount.value == null) return notSignedIn()
        return lock("comment:$workId").withLock {
            val stored = works.get(workId)
            val usable = stored?.takeIf { !it.stale && it.value.comment?.let { f -> matchesChapter(f, chapterId) } == true }
            val pageUrl = usable?.pageUrl ?: if (chapterId != null) Ao3Urls.chapter(workId, chapterId) else stored?.pageUrl ?: Ao3Urls.chapter(workId, null)
            val start = usable?.value ?: (fetchWork(workId, pageUrl).let { it.ok() ?: return@withLock it as Loaded.Error })
            perform(start, workSpec(
                workId, pageUrl,
                form = { a -> a.comment?.takeIf { matchesChapter(it, chapterId) } },
                done = { false },
                confirm = null,
                notice = { html -> WorkActionsParser.successNotice(html, COMMENT_CREATED) },
                overrides = listOf(WorkActionsParser.COMMENT to text),
            ))
        }
    }

    // ---- engine ----

    private sealed interface Sent {
        data class Done(val response: FetchResult.Ok) : Sent
        data class Retry(val reason: String) : Sent
        data class Fail(val error: Loaded.Error) : Sent
    }

    /** How one action reads, posts and confirms an item of state [S]. */
    private class Spec<S>(
        /** Re-GET the page the form came from (signed in); stores the state. */
        val fetch: suspend () -> Loaded<S>,
        val form: suspend (S) -> Ao3Form?,
        /** Already in effect: nothing (more) to post. */
        val done: (S) -> Boolean,
        /** The page shows the action took effect; null: only the item's own page or [notice] confirms. */
        val confirm: ((S) -> Boolean)?,
        /** The answer as the item's own signed-in page (stored when it is), else null. */
        val ownPage: (FetchResult.Ok) -> S?,
        /** A recognised success message in the answer. */
        val notice: (String) -> Boolean = { false },
        /** Confirmed by [notice] alone: what the stale state may assume. */
        val trust: () -> Unit = {},
        /** Something went wrong after a POST: the next action reads AO3's state first. */
        val stale: () -> Unit,
        val overrides: List<Pair<String, String>> = emptyList(),
    )

    private fun workSpec(
        workId: Long,
        pageUrl: HttpUrl,
        form: suspend (WorkActions) -> Ao3Form?,
        done: (WorkActions) -> Boolean,
        confirm: ((WorkActions) -> Boolean)?,
        notice: (String) -> Boolean = { false },
        trust: (WorkActions) -> WorkActions = { it },
        overrides: List<Pair<String, String>> = emptyList(),
    ): Spec<WorkActions> {
        val acct = account
        return Spec(
        fetch = { fetchWork(workId, pageUrl) },
        form = form,
        done = done,
        confirm = confirm,
        ownPage = { resp ->
            val user = ownUser(resp.html)
            if (user == null) null
            else WorkActionsParser.parse(resp.html, resp.finalUrl)
                .takeIf { WorkActionsParser.workId(it) == workId }
                ?.let { putWork(workId, it, pageUrl, user) }
        },
        notice = notice,
        trust = { asAccount(acct) { works.markStale(workId, trust) } },
        stale = { asAccount(acct) { works.markStale(workId) { it } } },
        overrides = overrides,
        )
    }

    /** The retry rule, then confirmation. Ok only when confirmed (or already in effect before any POST). */
    private suspend fun <S> perform(initial: S, spec: Spec<S>): Loaded<Unit> {
        var s = initial
        var f = spec.form(s)
        if (f == null) {
            s = spec.fetch().let { it.ok() ?: return it as Loaded.Error }
            if (spec.done(s)) return Loaded.Ok(Unit)
            f = spec.form(s) ?: return failed("form not found")
        }
        val first: Ao3Form = f
        // From the first POST on, the outcome and its bookkeeping must not be lost to a cancellation.
        return withContext(NonCancellable) {
            val reason = when (val sent = send(first, spec.overrides)) {
                is Sent.Done -> return@withContext settle(sent.response, spec)
                is Sent.Fail -> { spec.stale(); return@withContext sent.error }
                is Sent.Retry -> sent.reason
            }
            // Re-GET for a fresh token, and to see whether the first POST took effect after all.
            val refetched = spec.fetch()
            val fresh = refetched.ok() ?: run {
                spec.stale()
                return@withContext refetched as? Loaded.Error ?: failed("AO3 refused it: $reason")
            }
            if (spec.done(fresh)) return@withContext Loaded.Ok(Unit)
            val second = spec.form(fresh) ?: return@withContext failed("form not found")
            when (val sent = send(second, spec.overrides)) {
                is Sent.Done -> settle(sent.response, spec)
                is Sent.Fail -> { spec.stale(); sent.error }
                is Sent.Retry -> { spec.stale(); failed("AO3 refused it twice: ${sent.reason}") }
            }
        }
    }

    /** After a 2xx: the item's own page, a recognised notice, or a fresh GET must show the action. */
    private suspend fun <S> settle(resp: FetchResult.Ok, spec: Spec<S>): Loaded<Unit> {
        val own = spec.ownPage(resp)
        if (spec.notice(resp.html)) {
            if (own == null) spec.trust()
            return Loaded.Ok(Unit)
        }
        val confirm = spec.confirm
        if (confirm == null) {
            if (own != null) return Loaded.Ok(Unit)
            spec.stale()
            return unconfirmed()
        }
        val page = own ?: spec.fetch().ok() ?: run { spec.stale(); return unconfirmed() }
        return if (confirm(page)) Loaded.Ok(Unit) else unconfirmed()
    }

    private suspend fun send(form: Ao3Form, overrides: List<Pair<String, String>>): Sent {
        return when (val r = session.authedPost(form, overrides)) {
            is FetchResult.Ok -> {
                if (!session.state.value.signedIn) return Sent.Fail(notSignedIn())
                val err = if (WorkActionsParser.kudosAlreadyLeft(r.html)) null else WorkActionsParser.actionError(r.html)
                if (err == null) Sent.Done(r) else Sent.Retry(err)
            }
            FetchResult.Blocked -> Sent.Retry("blocked by Cloudflare")
            is FetchResult.Failed -> when (val c = r.cause) {
                is SessionRenewedException -> Sent.Retry("session renewed")
                // Known signed out and no re-login possible: Session sent nothing.
                is NotSignedInException -> Sent.Fail(notSignedIn())
                is HttpStatusException -> if (c.code == 422) Sent.Retry("HTTP 422") else Sent.Fail(mayHaveTakenEffect(r))
                else -> Sent.Fail(mayHaveTakenEffect(r))
            }
            // Not processed: AO3 (or Cloudflare) turned the request away.
            is FetchResult.RateLimited, FetchResult.NotFound -> Sent.Fail(Loaded.Error(r))
        }
    }

    private suspend fun toggleWork(
        workId: Long,
        kind: String,
        get: (WorkActions) -> Boolean,
        set: (WorkActions, Boolean) -> WorkActions,
        form: (WorkActions) -> Ao3Form?,
    ): Loaded<Boolean> {
        syncAccount()
        if (session.actingAccount.value == null) return notSignedIn()
        val m = lock("$kind:$workId")
        if (!m.tryLock()) return Loaded.Ok(works.flow.value[workId]?.let(get) ?: false)
        try {
            val known = currentWork(workId).let { it.ok() ?: return it as Loaded.Error }
            val target = !get(known.value)
            works.overlay(workId, kind) { set(it, target) }
            try {
                val r = perform(known.value, workSpec(
                    workId, known.pageUrl,
                    form = { form(it) },
                    done = { get(it) == target },
                    confirm = { get(it) == target },
                ))
                return when (r) {
                    is Loaded.Ok -> Loaded.Ok(target)
                    is Loaded.Error -> r
                }
            } finally {
                works.clearOverlay(workId, kind)
            }
        } finally {
            m.unlock()
        }
    }

    private class Known(val value: WorkActions, val pageUrl: HttpUrl)

    /** The stored state, or (unknown or stale) a fresh GET of its page. */
    private suspend fun currentWork(workId: Long): Loaded<Known> {
        val e = works.get(workId)
        if (e != null && !e.stale) return Loaded.Ok(Known(e.value, e.pageUrl))
        val url = e?.pageUrl ?: Ao3Urls.chapter(workId, null)
        return when (val r = fetchWork(workId, url)) {
            is Loaded.Ok -> Loaded.Ok(Known(r.value, url))
            is Loaded.Error -> r
        }
    }

    private suspend fun fetchWork(workId: Long, url: HttpUrl): Loaded<WorkActions> {
        val r = session.authedGet(url)
        if (r !is FetchResult.Ok) return Loaded.Error(r)
        val user = ownUser(r.html)
        if (!session.state.value.signedIn || user == null) return notSignedIn()
        val a = WorkActionsParser.parse(r.html, r.finalUrl)
        if (WorkActionsParser.workId(a)?.let { it != workId } == true) return failed("not this work's page")
        return putWork(workId, a, url, user)?.let { Loaded.Ok(it) } ?: notSignedIn()
    }

    /** Stores [a] under the list's [key] (whatever [url] it was fetched from). */
    private suspend fun fetchList(key: String, url: HttpUrl): Loaded<ListActions> {
        val r = session.authedGet(url)
        if (r !is FetchResult.Ok) return Loaded.Error(r)
        val user = ownUser(r.html)
        if (!session.state.value.signedIn || user == null) return notSignedIn()
        val l = WorkActionsParser.parseList(r.html, r.finalUrl)
        if (l.subscribe == null) return failed("subscribe form not found")
        if (!asAccount(user) { lists.put(key, l, url) }) return notSignedIn()
        return Loaded.Ok(l)
    }

    /** Stores [a] (a page of [user]), keeping a confirmed kudos; returns what was stored, null when [user] no longer acts. */
    private fun putWork(workId: Long, a: WorkActions, pageUrl: HttpUrl, user: String): WorkActions? {
        var stored: WorkActions? = null
        asAccount(user) {
            if (a.kudosGiven) kudosed += workId
            val v = if (!a.kudosGiven && workId in kudosed) a.copy(kudosGiven = true) else a
            works.put(workId, v, pageUrl)
            stored = v
        }
        return stored
    }

    private suspend fun bookmarkFormFor(a: WorkActions): Ao3Form? {
        val f = a.bookmarkForm
        if (!a.bookmarked || (f != null && isEditForm(f))) return f
        val edit = a.bookmarkEditUrl ?: return null
        val r = session.authedGet(edit)
        if (r !is FetchResult.Ok || !session.state.value.signedIn) return null
        return WorkActionsParser.bookmarkForm(r.html, r.finalUrl)?.takeIf(::isEditForm)
    }

    /** The page's account, lower-cased, when it is signed in as the acting account; else null (never used). */
    private fun ownUser(html: String): String? {
        val s = AccountParser.state(html)
        val me = session.actingAccount.value ?: return null
        return if (s.signedIn && s.username.equals(me, ignoreCase = true)) me.lowercase() else null
    }

    /**
     * Runs [write] only while [user] (lower-cased) is still the acting account, atomically with [syncAccount]:
     * a call that started under one account never writes into another's state. True when it ran.
     */
    @Synchronized private fun asAccount(user: String?, write: () -> Unit): Boolean {
        syncAccount()
        if (user == null || user != account) return false
        write()
        return true
    }

    /** Drops all state when the acting account changed since it was gathered. */
    @Synchronized private fun syncAccount() {
        val now = session.actingAccount.value?.lowercase()
        if (now != account) {
            account = now
            clear()
        }
    }

    private fun lock(key: String): Mutex = locks.computeIfAbsent(key) { Mutex() }

    private fun <T> Loaded<T>.ok(): T? = (this as? Loaded.Ok)?.value

    private fun notSignedIn() = Loaded.Error(FetchResult.Failed(NotSignedInException()))
    private fun notAList() = Loaded.Error(FetchResult.Failed(IllegalArgumentException("not a series or author list")))
    private fun failed(why: String) = Loaded.Error(FetchResult.Failed(ActionFailedException(why)))
    private fun unconfirmed() = Loaded.Error(FetchResult.Failed(ActionUnconfirmedException("AO3 didn't show the change")))
    private fun mayHaveTakenEffect(r: FetchResult) = Loaded.Error(FetchResult.Failed(ActionMayHaveTakenEffectException(r)))

    companion object {
        private val bookmarkPath = Regex("^/bookmarks/\\d+/?$")
        private const val KUDOS_THANKS = "Thank you for leaving kudos"
        private const val COMMENT_CREATED = "Comment created"

        /**
         * The [listState] key of a list URL: its subscribe page ([Ao3Urls.subscribePage]) with the user name
         * lower-cased (AO3 user names are case-insensitive). Null when [listUrl] is not a series or author list.
         */
        fun listKey(listUrl: HttpUrl): String? {
            val page = Ao3Urls.subscribePage(listUrl) ?: return null
            val seg = page.pathSegments
            return if (seg[0] == "users") Ao3Urls.user(seg[1].lowercase()).toString() else page.toString()
        }

        private fun isEditForm(f: Ao3Form) = f.method == "put" || bookmarkPath.matches(f.action.encodedPath)

        private fun matchesChapter(f: Ao3Form, chapterId: Long?) =
            chapterId == null || f.action.encodedPath == "/chapters/$chapterId/comments"

        private fun matches(saved: BookmarkInput, input: BookmarkInput) =
            saved.isPrivate == input.isPrivate && saved.isRec == input.isRec

        private fun sameSubscribable(a: Ao3Form, b: Ao3Form?): Boolean =
            b == null || (a.last("subscription[subscribable_id]") == b.last("subscription[subscribable_id]") &&
                a.last("subscription[subscribable_type]") == b.last("subscription[subscribable_type]"))

        /** Rails checkbox pairs: the hidden "0" stays, a "1" after it when checked (Rails reads the last). */
        private fun bookmarkOverrides(input: BookmarkInput): List<Pair<String, String>> = buildList {
            add(WorkActionsParser.NOTES to input.notes)
            add(WorkActionsParser.TAGS to input.tags.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", "))
            add(WorkActionsParser.PRIVATE to "0")
            if (input.isPrivate) add(WorkActionsParser.PRIVATE to "1")
            add(WorkActionsParser.REC to "0")
            if (input.isRec) add(WorkActionsParser.REC to "1")
        }
    }
}

/** Known values per key with their page URL, plus pending overlays (optimistic toggles); [flow] shows both. */
private class Book<K : Any, S : Any> {
    class Entry<S>(val value: S, val pageUrl: HttpUrl, val stale: Boolean)

    private val entries = HashMap<K, Entry<S>>()
    private val overlays = HashMap<K, LinkedHashMap<String, (S) -> S>>()
    private val _flow = MutableStateFlow<Map<K, S>>(emptyMap())
    val flow: StateFlow<Map<K, S>> = _flow.asStateFlow()

    @Synchronized fun get(k: K): Entry<S>? = entries[k]

    @Synchronized fun put(k: K, v: S, pageUrl: HttpUrl) {
        entries[k] = Entry(v, pageUrl, stale = false)
        publish()
    }

    /** Applies [change] to the known value, keeping its stale flag. */
    @Synchronized fun modify(k: K, change: (S) -> S) {
        val e = entries[k] ?: return
        entries[k] = Entry(change(e.value), e.pageUrl, e.stale)
        publish()
    }

    /** Applies [change] to the known value and marks its forms stale. */
    @Synchronized fun markStale(k: K, change: (S) -> S) {
        val e = entries[k] ?: return
        entries[k] = Entry(change(e.value), e.pageUrl, stale = true)
        publish()
    }

    @Synchronized fun overlay(k: K, name: String, f: (S) -> S) {
        overlays.getOrPut(k) { LinkedHashMap() }[name] = f
        publish()
    }

    @Synchronized fun clearOverlay(k: K, name: String) {
        overlays[k]?.let { it.remove(name); if (it.isEmpty()) overlays.remove(k) }
        publish()
    }

    @Synchronized fun clear() {
        entries.clear()
        overlays.clear()
        publish()
    }

    private fun publish() {
        _flow.value = entries.mapValues { (k, e) -> overlays[k]?.values?.fold(e.value) { acc, f -> f(acc) } ?: e.value }
    }
}
