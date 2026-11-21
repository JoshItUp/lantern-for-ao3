package app.ao3reader.data

import app.ao3reader.account.Session
import app.ao3reader.account.SessionRenewedException
import app.ao3reader.ao3.net.FetchResult
import app.ao3reader.ao3.parse.AccountParser
import app.ao3reader.ao3.parse.Ao3Form
import app.ao3reader.ao3.parse.Ao3Urls
import app.ao3reader.ao3.parse.BookmarkInput
import app.ao3reader.ao3.parse.ListActions
import app.ao3reader.ao3.parse.WorkActions
import app.ao3reader.ao3.parse.WorkActionsParser
import app.ao3reader.ao3.parse.last
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** An action AO3 didn't take: the form is missing, or AO3 kept answering with an error ([message] is AO3's text). */
class ActionFailedException(message: String) : Exception(message)

/**
 * The owner's actions on works (kudos, bookmark, mark for later, subscribe, comment) and on series and
 * authors (subscribe), posted through [Session.authedPost] with the forms of the last signed-in page seen.
 *
 * Retry rule, per call: submit once. On a 422, a flash error, `Blocked` or a renewed session, re-GET the
 * page the form came from, check whether the action already took effect (then it is a success and nothing
 * is posted again), else submit the fresh form once more. Then give up. Never loops.
 *
 * Toggles are per key: a tap while one is in flight is ignored (it reports the pending value), [state] /
 * [listState] show the pending value at once and roll back when the call fails.
 *
 * Form fields and comment text are never logged.
 */
class ActionRepository(private val session: Session) {
    private val works = Book<Long, WorkActions>()
    private val lists = Book<String, ListActions>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** Per work id: the latest known actions, pending toggles applied. */
    val state: StateFlow<Map<Long, WorkActions>> = works.flow
    /** Per subscribe page URL ([Ao3Urls.subscribePage] of a list URL): the latest known, pending toggles applied. */
    val listState: StateFlow<Map<String, ListActions>> = lists.flow

    /** From any signed-in page of [workId]; [pageUrl] is where its forms are fetched again. Other pages are ignored. */
    fun update(workId: Long, html: String, pageUrl: HttpUrl) {
        if (!isOwnPage(html)) return
        val a = WorkActionsParser.parse(html, pageUrl)
        if (WorkActionsParser.workId(a)?.let { it != workId } == true) return
        works.put(workId, a, pageUrl)
    }

    /** From a signed-in series or user page of the list at [listUrl]; pages without the subscribe form are ignored. */
    fun updateList(listUrl: HttpUrl, html: String, pageUrl: HttpUrl) {
        val key = Ao3Urls.subscribePage(listUrl) ?: return
        if (!isOwnPage(html)) return
        val l = WorkActionsParser.parseList(html, pageUrl)
        if (l.subscribe == null) return
        lists.put(key.toString(), l, pageUrl)
    }

    /** The owner's bookmark on [workId] as the known edit form holds it; null when not bookmarked or no edit form is known. */
    fun existingBookmark(workId: Long): BookmarkInput? {
        val a = works.get(workId)?.value ?: return null
        val f = a.bookmarkForm?.takeIf { a.bookmarked && isEditForm(it) } ?: return null
        return WorkActionsParser.bookmarkInput(f)
    }

    /** Forgets everything (sign-out, account change). */
    fun clear() {
        works.clear()
        lists.clear()
    }

    /** GETs the work page (the last one seen, else the work) and updates [state]. */
    suspend fun refresh(workId: Long): Loaded<WorkActions> {
        if (session.actingAccount.value == null) return notSignedIn()
        return fetchWork(workId, works.get(workId)?.pageUrl ?: Ao3Urls.chapter(workId, null))
    }

    /** GETs the subscribe page of the list at [listUrl] and updates [listState]. */
    suspend fun refreshList(listUrl: HttpUrl): Loaded<ListActions> {
        val key = Ao3Urls.subscribePage(listUrl) ?: return notAList()
        if (session.actingAccount.value == null) return notSignedIn()
        return fetchList(key)
    }

    suspend fun kudos(workId: Long): Loaded<Unit> {
        if (session.actingAccount.value == null) return notSignedIn()
        return lock("kudos:$workId").withLock {
            val known = currentWork(workId).let { it.ok() ?: return@withLock it as Loaded.Error }
            if (known.value.kudosGiven) return@withLock Loaded.Ok(Unit)
            val r = perform(
                initial = known.value,
                fetch = { fetchWork(workId, known.pageUrl) },
                form = { it.kudos },
                done = { it.kudosGiven },
                alreadyOk = WorkActionsParser::kudosAlreadyLeft,
            )
            when (r) {
                is Loaded.Ok -> {
                    afterWork(workId, r.value) { it.copy(kudosGiven = true) }
                    Loaded.Ok(Unit)
                }
                is Loaded.Error -> r
            }
        }
    }

    suspend fun toggleMarkForLater(workId: Long): Loaded<Boolean> =
        toggleWork(workId, "mfl", { it.markedForLater }, { a, v -> a.copy(markedForLater = v) }) { it.markForLater }

    suspend fun toggleSubscribe(workId: Long): Loaded<Boolean> =
        toggleWork(workId, "sub", { it.subscribed }, { a, v -> a.copy(subscribed = v) }) { it.subscribe }

    /** Subscribe to or unsubscribe from a series or an author; [listUrl] is any in-app work list URL ([Ao3Urls.workList]). */
    suspend fun toggleListSubscribe(listUrl: HttpUrl): Loaded<Boolean> {
        val page = Ao3Urls.subscribePage(listUrl) ?: return notAList()
        if (session.actingAccount.value == null) return notSignedIn()
        val key = page.toString()
        val m = lock("list:$key")
        if (!m.tryLock()) return Loaded.Ok(lists.flow.value[key]?.subscribed ?: false)
        try {
            val known = lists.get(key)?.takeIf { !it.stale }?.let { Loaded.Ok(it.value) } ?: fetchList(page)
            val start = known.ok() ?: return known as Loaded.Error
            val target = !start.subscribed
            lists.overlay(key, "sub") { it.copy(subscribed = target) }
            try {
                val r = perform(
                    initial = start,
                    fetch = { fetchList(lists.get(key)?.pageUrl ?: page) },
                    form = { it.subscribe },
                    done = { it.subscribed == target },
                )
                return when (r) {
                    is Loaded.Ok -> {
                        val resp = r.value
                        val parsed = resp?.let { WorkActionsParser.parseList(it.html, it.finalUrl) }
                        if (resp != null && parsed?.subscribe != null && sameSubscribable(parsed.subscribe!!, start.subscribe)) {
                            lists.put(key, parsed, lists.get(key)?.pageUrl ?: page)
                        } else if (resp != null) {
                            lists.markStale(key) { it.copy(subscribed = target) }
                        }
                        Loaded.Ok(lists.get(key)?.value?.subscribed ?: target)
                    }
                    is Loaded.Error -> r
                }
            } finally {
                lists.clearOverlay(key, "sub")
            }
        } finally {
            m.unlock()
        }
    }

    /** Creates the bookmark, or edits the existing one (fetching its edit page when only the link is known). */
    suspend fun saveBookmark(workId: Long, input: BookmarkInput): Loaded<Unit> {
        if (session.actingAccount.value == null) return notSignedIn()
        return lock("bookmark:$workId").withLock {
            val known = currentWork(workId).let { it.ok() ?: return@withLock it as Loaded.Error }
            val creating = !known.value.bookmarked
            val r = perform(
                initial = known.value,
                fetch = { fetchWork(workId, known.pageUrl) },
                form = { a -> bookmarkFormFor(a) },
                done = { creating && it.bookmarked },
                overrides = bookmarkOverrides(input),
            )
            when (r) {
                is Loaded.Ok -> {
                    afterWork(workId, r.value) { it.copy(bookmarked = true) }
                    Loaded.Ok(Unit)
                }
                is Loaded.Error -> r
            }
        }
    }

    /** Posts [text] as-is to [chapterId]'s comment form (the known page's when null). Blank text sends nothing. */
    suspend fun comment(workId: Long, chapterId: Long?, text: String): Loaded<Unit> {
        if (text.isBlank()) return Loaded.Error(FetchResult.Failed(IllegalArgumentException("comment is blank")))
        if (session.actingAccount.value == null) return notSignedIn()
        return lock("comment:$workId").withLock {
            val stored = works.get(workId)
            val usable = stored?.takeIf { !it.stale && it.value.comment?.let { f -> matchesChapter(f, chapterId) } == true }
            val pageUrl = usable?.pageUrl ?: if (chapterId != null) Ao3Urls.chapter(workId, chapterId) else stored?.pageUrl ?: Ao3Urls.chapter(workId, null)
            val start = usable?.value ?: (fetchWork(workId, pageUrl).let { it.ok() ?: return@withLock it as Loaded.Error })
            val r = perform(
                initial = start,
                fetch = { fetchWork(workId, pageUrl) },
                form = { a -> a.comment?.takeIf { matchesChapter(it, chapterId) } },
                done = { false },
                overrides = listOf(WorkActionsParser.COMMENT to text),
            )
            when (r) {
                is Loaded.Ok -> {
                    afterWork(workId, r.value) { it }
                    Loaded.Ok(Unit)
                }
                is Loaded.Error -> r
            }
        }
    }

    // ---- engine ----

    private sealed interface Sent {
        data class Done(val response: FetchResult.Ok) : Sent
        data class Retry(val reason: String) : Sent
        data class Fail(val error: Loaded.Error) : Sent
    }

    /**
     * The retry rule. Ok(response) when a POST succeeded, Ok(null) when a re-GET showed the action already in
     * effect (state is updated from that page).
     */
    private suspend fun <S> perform(
        initial: S,
        fetch: suspend () -> Loaded<S>,
        form: suspend (S) -> Ao3Form?,
        done: (S) -> Boolean,
        overrides: List<Pair<String, String>> = emptyList(),
        alreadyOk: (String) -> Boolean = { false },
    ): Loaded<FetchResult.Ok?> {
        var s = initial
        var f = form(s)
        if (f == null) {
            s = fetch().let { it.ok() ?: return it as Loaded.Error }
            if (done(s)) return Loaded.Ok(null)
            f = form(s) ?: return failed("form not found")
        }
        val reason: String
        when (val first = send(f, overrides, alreadyOk)) {
            is Sent.Done -> return Loaded.Ok(first.response)
            is Sent.Fail -> return first.error
            is Sent.Retry -> reason = first.reason
        }
        // Re-GET for a fresh token (and to see whether the first POST took effect after all).
        s = fetch().let { it.ok() ?: return it as Loaded.Error }
        if (done(s)) return Loaded.Ok(null)
        f = form(s) ?: return failed("form not found")
        return when (val second = send(f, overrides, alreadyOk)) {
            is Sent.Done -> Loaded.Ok(second.response)
            is Sent.Fail -> second.error
            is Sent.Retry -> failed("AO3 refused it twice: ${second.reason.ifEmpty { reason }}")
        }
    }

    private suspend fun send(form: Ao3Form, overrides: List<Pair<String, String>>, alreadyOk: (String) -> Boolean): Sent {
        return when (val r = session.authedPost(form, overrides)) {
            is FetchResult.Ok -> {
                if (!session.state.value.signedIn) return Sent.Fail(notSignedIn())
                if (alreadyOk(r.html)) return Sent.Done(r)
                val err = WorkActionsParser.actionError(r.html)
                if (err == null) Sent.Done(r) else Sent.Retry(err)
            }
            FetchResult.Blocked -> Sent.Retry("blocked by Cloudflare")
            is FetchResult.Failed -> when {
                r.cause is SessionRenewedException -> Sent.Retry("session renewed")
                r.cause is IOException && r.cause?.message == "HTTP 422" -> Sent.Retry("HTTP 422")
                else -> Sent.Fail(Loaded.Error(r))
            }
            else -> Sent.Fail(Loaded.Error(r))
        }
    }

    private suspend fun toggleWork(
        workId: Long,
        kind: String,
        get: (WorkActions) -> Boolean,
        set: (WorkActions, Boolean) -> WorkActions,
        form: (WorkActions) -> Ao3Form?,
    ): Loaded<Boolean> {
        if (session.actingAccount.value == null) return notSignedIn()
        val m = lock("$kind:$workId")
        if (!m.tryLock()) return Loaded.Ok(works.flow.value[workId]?.let(get) ?: false)
        try {
            val known = currentWork(workId).let { it.ok() ?: return it as Loaded.Error }
            val target = !get(known.value)
            works.overlay(workId, kind) { set(it, target) }
            try {
                val r = perform(
                    initial = known.value,
                    fetch = { fetchWork(workId, known.pageUrl) },
                    form = { form(it) },
                    done = { get(it) == target },
                )
                return when (r) {
                    is Loaded.Ok -> {
                        afterWork(workId, r.value) { set(it, target) }
                        Loaded.Ok(works.get(workId)?.value?.let(get) ?: target)
                    }
                    is Loaded.Error -> r
                }
            } finally {
                works.clearOverlay(workId, kind)
            }
        } finally {
            m.unlock()
        }
    }

    /**
     * After a successful POST: the response page, when it is this work's signed-in page, becomes the state
     * (with [trust] applied for what the page may not show); otherwise [trust] is applied to the known state
     * and its forms are marked stale (the next action GETs the page first).
     */
    private fun afterWork(workId: Long, response: FetchResult.Ok?, trust: (WorkActions) -> WorkActions) {
        response ?: return // a re-GET found it already done; state came from that page
        val parsed = if (isOwnPage(response.html)) WorkActionsParser.parse(response.html, response.finalUrl) else null
        val pageUrl = works.get(workId)?.pageUrl ?: Ao3Urls.chapter(workId, null)
        if (parsed != null && WorkActionsParser.workId(parsed) == workId) {
            works.put(workId, trustOnlyKudos(parsed, trust), pageUrl)
        } else {
            works.markStale(workId, trust)
        }
    }

    /** The page is the truth for toggles and bookmarks; kudos lists may truncate, so kudos given is kept. */
    private fun trustOnlyKudos(parsed: WorkActions, trust: (WorkActions) -> WorkActions): WorkActions =
        if (trust(parsed).kudosGiven && !parsed.kudosGiven) parsed.copy(kudosGiven = true) else parsed

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
        if (!session.state.value.signedIn || !isOwnPage(r.html)) return notSignedIn()
        val a = WorkActionsParser.parse(r.html, r.finalUrl)
        if (WorkActionsParser.workId(a)?.let { it != workId } == true) return failed("not this work's page")
        works.put(workId, a, url)
        return Loaded.Ok(a)
    }

    private suspend fun fetchList(page: HttpUrl): Loaded<ListActions> {
        val r = session.authedGet(page)
        if (r !is FetchResult.Ok) return Loaded.Error(r)
        if (!session.state.value.signedIn || !isOwnPage(r.html)) return notSignedIn()
        val l = WorkActionsParser.parseList(r.html, r.finalUrl)
        if (l.subscribe == null) return failed("subscribe form not found")
        lists.put(page.toString(), l, page)
        return Loaded.Ok(l)
    }

    private suspend fun bookmarkFormFor(a: WorkActions): Ao3Form? {
        val f = a.bookmarkForm
        if (!a.bookmarked || (f != null && isEditForm(f))) return f
        val edit = a.bookmarkEditUrl ?: return null
        val r = session.authedGet(edit)
        if (r !is FetchResult.Ok || !session.state.value.signedIn) return null
        return WorkActionsParser.bookmarkForm(r.html, r.finalUrl)?.takeIf(::isEditForm)
    }

    /** Signed in as the acting account (a page of another account is never used). */
    private fun isOwnPage(html: String): Boolean {
        val s = AccountParser.state(html)
        val me = session.actingAccount.value ?: return false
        return s.signedIn && s.username.equals(me, ignoreCase = true)
    }

    private fun lock(key: String): Mutex = locks.computeIfAbsent(key) { Mutex() }

    private fun <T> Loaded<T>.ok(): T? = (this as? Loaded.Ok)?.value

    private fun notSignedIn() = Loaded.Error(FetchResult.Failed(NotSignedInException()))
    private fun notAList() = Loaded.Error(FetchResult.Failed(IllegalArgumentException("not a series or author list")))
    private fun failed(why: String) = Loaded.Error(FetchResult.Failed(ActionFailedException(why)))

    private companion object {
        private val bookmarkPath = Regex("^/bookmarks/\\d+/?$")

        fun isEditForm(f: Ao3Form) = f.method == "put" || bookmarkPath.matches(f.action.encodedPath)

        fun matchesChapter(f: Ao3Form, chapterId: Long?) =
            chapterId == null || f.action.encodedPath == "/chapters/$chapterId/comments"

        fun sameSubscribable(a: Ao3Form, b: Ao3Form?): Boolean =
            b == null || (a.last("subscription[subscribable_id]") == b.last("subscription[subscribable_id]") &&
                a.last("subscription[subscribable_type]") == b.last("subscription[subscribable_type]"))

        /** Rails checkbox pairs: the hidden "0" stays, a "1" after it when checked (Rails reads the last). */
        fun bookmarkOverrides(input: BookmarkInput): List<Pair<String, String>> = buildList {
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
