package app.ao3reader.ui.reader

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.ao3reader.AppContainer
import app.ao3reader.ao3.model.ChapterContent
import app.ao3reader.ao3.model.ChapterRef
import app.ao3reader.ao3.net.FetchResult
import app.ao3reader.ao3.parse.BookmarkInput
import app.ao3reader.ao3.parse.WorkActions
import app.ao3reader.data.ActionMayHaveTakenEffectException
import app.ao3reader.data.ActionRunner
import app.ao3reader.data.ActionUnconfirmedException
import app.ao3reader.data.ChapterSource
import app.ao3reader.data.WorkActionSource
import app.ao3reader.ui.common.ActionMessage
import app.ao3reader.ui.common.COMMENT_MAY_NOT_HAVE_POSTED
import app.ao3reader.ui.common.WorkAction
import app.ao3reader.ui.common.actionFeedback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import app.ao3reader.data.Loaded
import app.ao3reader.data.ReaderFont
import app.ao3reader.data.SettingsStore
import app.ao3reader.data.db.DownloadEntity
import app.ao3reader.data.db.ProgressDao
import app.ao3reader.data.db.ProgressEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import app.ao3reader.ui.Routes
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Immutable
data class ReaderUiState(
    val workId: Long,
    val chapters: List<ChapterRef> = emptyList(),
    val index: Int = 0,
    val content: ChapterContent? = null,
    val loading: Boolean = true,
    val error: FetchResult? = null,
    /** blockIndex (LazyColumn item index), offsetPx to scroll to once after load; cleared by [ReaderViewModel.restoreApplied]. */
    val restore: Pair<Int, Int>? = null,
    val controlsVisible: Boolean = false,
    val fontSizeSp: Int = 18,
    val font: ReaderFont = ReaderFont.SYSTEM,
    /** False when the chapter list failed and [chapters] is a single fallback entry from the loaded chapter. */
    val chapterListLoaded: Boolean = true,
    /** True once the current load has run for [ReaderViewModel.SPINNER_DELAY_MS]; fast (cached) loads never show a spinner. */
    val showSpinner: Boolean = false,
    /** This work's download row (null: never downloaded). */
    val download: DownloadEntity? = null,
)

/** The account actions for the work being read: what the action row shows. */
@Immutable
data class ActionsUi(
    /** False: the row is replaced by a sign-in button. */
    val signedIn: Boolean = false,
    /** AO3's state for this work has arrived (from a signed-in page); until then everything reads "not done". */
    val known: Boolean = false,
    val kudosGiven: Boolean = false,
    val bookmarked: Boolean = false,
    val markedForLater: Boolean = false,
    val subscribed: Boolean = false,
    /** Actions AO3 offers on this work (all of them until its state is known). */
    val available: Set<WorkAction> = WorkAction.entries.toSet(),
    /** Actions with a request in flight: their buttons show progress. */
    val running: Set<WorkAction> = emptySet(),
)

/** The bottom sheet open over the reader, if any. */
sealed interface ActionSheet {
    /**
     * [loading]: AO3's bookmark is being read first. [note]: a warning shown above Save. [error]: the last
     * save failed (shown in the sheet: a snackbar would sit under its scrim).
     */
    data class Bookmark(
        val loading: Boolean, val initial: BookmarkInput, val existing: Boolean,
        val note: String? = null, val error: String? = null,
    ) : ActionSheet
    /**
     * The comment goes to this chapter (the one shown when the sheet opened). [error]: the last post
     * failed. [mustCheck]: it may have gone through, so Post stays off until the text is edited or the
     * owner says they checked. [failedOnce]: the button reads "Post again".
     */
    data class Comment(
        val chapterId: Long?, val chapterNumber: Int,
        val error: String? = null, val mustCheck: Boolean = false, val failedOnce: Boolean = false,
    ) : ActionSheet
}

/**
 * One chapter in memory at a time: changing chapter replaces [ReaderUiState.content]; the next
 * chapter is only prefetched to the disk cache.
 *
 * The current chapter is written to [savedState] under the route's argument keys, so after process
 * death the restored entry reopens the chapter being read rather than the one it was opened at.
 *
 * Account actions run through [runner] (an app-wide scope): leaving the reader never cancels a POST.
 * [actions] is derived narrowly from the repository's state for this work only.
 */
@OptIn(FlowPreview::class)
class ReaderViewModel(
    private val workId: Long,
    private val routeChapterId: Long?,
    private val routeNumber: Int,
    private val source: ChapterSource,
    private val progressDao: ProgressDao,
    private val settings: SettingsStore,
    private val savedState: SavedStateHandle = SavedStateHandle(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val actionSource: WorkActionSource? = null,
    private val runner: ActionRunner? = null,
    /** This work's download row ([app.ao3reader.data.download.DownloadRepository.observe]); null: no downloads. */
    private val downloadState: Flow<DownloadEntity?>? = null,
    /** Queues a download ([app.ao3reader.data.download.DownloadRepository.request]); null: no downloads. */
    private val requestDownload: (suspend (workId: Long, title: String, authors: List<String>) -> Unit)? = null,
) : ViewModel() {
    /**
     * The chapter to open: the handle's values when it has them (on a fresh open these are the
     * route arguments; after a chapter has loaded, including after process death, that chapter),
     * else the route's.
     */
    private val startChapterId: Long?
        get() = if (savedState.contains(Routes.ARG_CHAPTER_ID)) savedState.get<String>(Routes.ARG_CHAPTER_ID)?.toLongOrNull()
        else routeChapterId
    private val startNumber: Int get() = savedState.get<Int>(Routes.ARG_NUMBER) ?: routeNumber

    private val _state = MutableStateFlow(ReaderUiState(workId))
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    /** Latest progress for this work, as last read from or written to the database. */
    private var saved: ProgressEntity? = null
    private var loadJob: Job? = null
    /** Scroll position about to be written, captured with the chapter it belongs to. */
    private var pending: ProgressEntity? = null
    /**
     * Latest reported scroll position, kept as primitives: [onScroll] can run every frame of a scroll,
     * so it only stores these and pokes [scrollTicks]; the entity is built when it is written.
     */
    private var scrollDirty = false
    private var scrollBlock = 0
    private var scrollOffset = 0
    /** One debounced writer for the VM's lifetime instead of a coroutine launched per scroll report. */
    private val scrollTicks = Channel<Unit>(Channel.CONFLATED)
    private val writeLock = Mutex()
    /** Font writes in flight; settings emissions are ignored meanwhile so a slider drag doesn't jump back. */
    private var fontWrites = 0

    init {
        viewModelScope.launch {
            // Restarts per chapter: a new index or a retry starts a fresh delay.
            _state.map { it.loading to it.index }.distinctUntilChanged().collectLatest { (loading, _) ->
                _state.update { it.copy(showSpinner = false) }
                if (loading) {
                    delay(SPINNER_DELAY_MS)
                    _state.update { it.copy(showSpinner = it.loading) }
                }
            }
        }
        viewModelScope.launch {
            settings.fontSizeSp.collect { sp -> if (fontWrites == 0) _state.update { it.copy(fontSizeSp = sp) } }
        }
        viewModelScope.launch {
            settings.fontFamily.collect { f -> if (fontWrites == 0) _state.update { it.copy(font = f) } }
        }
        viewModelScope.launch {
            scrollTicks.receiveAsFlow().debounce(SAVE_DEBOUNCE_MS).collect { writePending() }
        }
        downloadState?.let { flow ->
            viewModelScope.launch { flow.collect { d -> _state.update { it.copy(download = d) } } }
        }
        viewModelScope.launch { source.listUpdates(workId).collect(::applyListUpdate) }
        open()
    }

    private fun open() {
        loadJob?.cancel()
        _state.update { it.copy(loading = true, error = null, content = null) }
        loadJob = viewModelScope.launch {
            saved = progressDao.get(workId)
            val initial = (source.chapters(workId) as? Loaded.Ok)?.value?.takeIf { it.isNotEmpty() }
            if (initial != null) {
                // The resume point is past the download: wait briefly for the online list so it opens there
                // (online), never at chapter 1. Offline, the stored list is used.
                val waited = !targetIn(initial) && downloadState?.first()?.hasCopy == true
                val list = if (!waited) initial
                else withTimeoutOrNull(LIST_UPDATE_WAIT_MS) { source.listUpdates(workId).first() }?.takeIf { it.isNotEmpty() } ?: initial
                val index = if (waited) downloadedStartIndex(list) else startIndex(list)
                _state.update { it.copy(chapters = list, index = index, chapterListLoaded = true) }
                loadChapter(index, list[index])
            } else {
                openWithoutList()
            }
        }
    }

    /** Whether the chapter to open (route or restored handle, else saved progress) is in [list]; true when there is none. */
    private fun targetIn(list: List<ChapterRef>): Boolean {
        // Null-tolerant: a stored one-shot has no chapter id, while a History route carries AO3's.
        startChapterId?.let { id -> return list.any { it.matches(id, startNumber) } }
        saved?.let { p -> return list.any { it.matches(p.chapterId, p.chapterNumber) } }
        return true
    }

    /** [startIndex], except that a target past the end of [list] (a download missing new chapters) opens its last chapter. */
    private fun downloadedStartIndex(list: List<ChapterRef>): Int {
        if (!targetIn(list)) {
            val number = if (startChapterId != null) startNumber else saved?.chapterNumber
            if (number != null && number > list.last().number) return list.lastIndex
        }
        return startIndex(list)
    }

    /** A longer list arrived (a downloaded work's online chapters): adopt it, keeping the chapter being read. */
    private fun applyListUpdate(list: List<ChapterRef>) {
        if (list.isEmpty() || list == _state.value.chapters) return
        _state.update { s ->
            // Nothing listed yet (open() is still deciding), or only the single-entry fallback: open() owns that.
            if (s.chapters.isEmpty() || !s.chapterListLoaded) return@update s
            val current = s.chapters.getOrNull(s.index)
            val index = current?.let { c -> list.indexOfFirst { it.matches(c.chapterId, c.number) } }?.takeIf { it >= 0 }
                ?: s.index.coerceAtMost(list.lastIndex)
            s.copy(chapters = list, index = index)
        }
    }

    /** Route (or restored handle) chapter, then saved progress, then the route number, then the first chapter. */
    private fun startIndex(list: List<ChapterRef>): Int {
        startChapterId?.let { id -> list.indexOfFirst { it.chapterId == id }.takeIf { it >= 0 }?.let { return it } }
        saved?.let { p -> list.indexOfFirst { it.matches(p.chapterId, p.chapterNumber) }.takeIf { it >= 0 }?.let { return it } }
        return list.indexOfFirst { it.number == startNumber }.coerceAtLeast(0)
    }

    /** Records [ref] as the current chapter for a restored back-stack entry (see the class doc). */
    private fun rememberChapter(ref: ChapterRef) {
        savedState[Routes.ARG_CHAPTER_ID] = ref.chapterId?.toString()
        savedState[Routes.ARG_NUMBER] = ref.number
    }

    /** The chapter list failed: load the chapter the route or progress points at, and list just that one. */
    private suspend fun openWithoutList() {
        val p = saved
        val startId = startChapterId
        val ref = when {
            startId != null -> ChapterRef(startId, startNumber, "")
            p != null -> ChapterRef(p.chapterId, p.chapterNumber, "")
            else -> ChapterRef(null, 1, "")
        }
        when (val r = source.chapter(workId, ref)) {
            is Loaded.Ok -> {
                val loadedRef = r.value.ref
                rememberChapter(loadedRef)
                _state.update {
                    it.copy(chapters = listOf(loadedRef), index = 0, chapterListLoaded = false, content = r.value,
                        loading = false, error = null, restore = restoreFor(loadedRef))
                }
            }
            is Loaded.Error -> _state.update { it.copy(loading = false, error = r.result) }
        }
    }

    private suspend fun loadChapter(index: Int, ref: ChapterRef) {
        when (val r = source.chapter(workId, ref)) {
            is Loaded.Ok -> {
                rememberChapter(ref)
                _state.update {
                    it.copy(content = r.value, loading = false, error = null, restore = restoreFor(ref))
                }
                _state.value.chapters.getOrNull(index + 1)?.let { source.prefetch(workId, it) }
            }
            is Loaded.Error -> _state.update { it.copy(loading = false, error = r.result) }
        }
    }

    private fun restoreFor(ref: ChapterRef): Pair<Int, Int>? =
        saved?.takeIf { ref.matches(it.chapterId, it.chapterNumber) }?.let { it.blockIndex to it.blockOffsetPx }

    fun goTo(index: Int) {
        val s = _state.value
        if (index !in s.chapters.indices) return
        if (index == s.index && (s.content != null || s.loading)) return
        flushProgress()
        loadJob?.cancel()
        // Drop the old chapter before loading the new one: only one chapter is held in memory.
        _state.update { it.copy(index = index, content = null, loading = true, error = null, restore = null) }
        val ref = s.chapters[index]
        rememberChapter(ref)
        loadJob = viewModelScope.launch { loadChapter(index, ref) }
    }

    fun next() = goTo(_state.value.index + 1)

    fun previous() = goTo(_state.value.index - 1)

    fun retry() {
        val s = _state.value
        if (s.loading) return
        // No list yet, or only the single-entry fallback: fetch the chapter list again first.
        if (s.chapters.isEmpty() || !s.chapterListLoaded) {
            flushProgress()
            open()
        } else {
            _state.update { it.copy(loading = true, error = null) }
            val ref = s.chapters[s.index]
            loadJob = viewModelScope.launch { loadChapter(s.index, ref) }
        }
    }

    /** This work's download row alone, so the download slot never recomposes with the chapter text. */
    val download: StateFlow<DownloadEntity?> = _state.map { it.download }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Download, update or retry. Runs on the app scope ([runner]) so leaving the reader never cancels it;
     * works signed out (downloads need no account). Nothing happens once the copy is current.
     */
    fun onDownload() {
        val s = _state.value
        val d = s.download
        if (downloadLabel(d) == DOWNLOADED_LABEL) return
        val req = requestDownload ?: return
        val title = s.content?.workTitle?.takeIf { it.isNotBlank() } ?: d?.title ?: ""
        val authors = s.content?.authors ?: d?.authors?.split(", ")?.filter { it.isNotEmpty() } ?: emptyList()
        val block: suspend () -> Unit = { req(workId, title, authors) }
        val r = runner
        if (r != null) r.launch(ActionRunner.workKey(workId, "DOWNLOAD"), block) else viewModelScope.launch { block() }
    }

    fun toggleControls() = _state.update { it.copy(controlsVisible = !it.controlsVisible) }

    /** Called by the screen once it has scrolled to [ReaderUiState.restore]. */
    fun restoreApplied() = _state.update { it.copy(restore = null) }

    /**
     * Written once reports have paused for [SAVE_DEBOUNCE_MS]; ignored until content is shown and any
     * restore has been applied. Cheap to call often: it stores the position and allocates nothing.
     */
    fun onScroll(blockIndex: Int, offsetPx: Int) {
        val s = _state.value
        if (s.content == null || s.restore != null) return
        scrollBlock = blockIndex
        scrollOffset = offsetPx
        scrollDirty = true
        scrollTicks.trySend(Unit)
    }

    /**
     * Turns the latest reported position into [pending], against the chapter shown now. Runs before
     * every write; a chapter change flushes first, so a position is never attributed to the next chapter.
     */
    private fun capturePending() {
        if (!scrollDirty) return
        scrollDirty = false
        val s = _state.value
        val content = s.content ?: return
        val ref = s.chapters.getOrNull(s.index) ?: content.ref
        pending = ProgressEntity(
            workId = workId,
            workTitle = content.workTitle,
            chapterId = ref.chapterId,
            chapterNumber = ref.number,
            // On the single-entry fallback list the total is unknown: keep the one already saved.
            chaptersTotal = if (s.chapterListLoaded) s.chapters.size else saved?.chaptersTotal,
            blockIndex = scrollBlock,
            blockOffsetPx = scrollOffset,
            updatedAt = 0L,
        )
    }

    /** Writes any pending position now (called on ON_STOP and before changing chapter). */
    fun flushProgress() {
        capturePending()
        if (pending == null) return
        viewModelScope.launch { withContext(NonCancellable) { writePending() } }
    }

    /**
     * The upsert runs to completion even if the calling job is cancelled, and [pending] is only
     * cleared once it is written, unless a newer position replaced it meanwhile. Writes are
     * serialised so an older position can't land after a newer one.
     */
    private suspend fun writePending() {
        capturePending()
        val p = pending ?: return
        val entity = p.copy(updatedAt = clock())
        saved = entity
        withContext(NonCancellable) {
            writeLock.withLock { progressDao.upsert(entity) }
            if (pending === p) pending = null
        }
    }

    // ---- account actions ----

    private val _messages = Channel<ActionMessage>(Channel.BUFFERED)
    /** One-off feedback for a snackbar. */
    val messages: Flow<ActionMessage> = _messages.receiveAsFlow()

    // An open comment sheet and its draft survive process death (savedState); the bookmark sheet re-reads AO3.
    private val _sheet = MutableStateFlow<ActionSheet?>(
        savedState.get<Int>(KEY_COMMENT_NUMBER)?.let { n ->
            commentSheet(
                savedState.get<String>(KEY_COMMENT_CHAPTER)?.toLongOrNull(), n,
                mustCheck = savedState.get<Boolean>(KEY_COMMENT_MUST_CHECK) ?: false,
                failedOnce = savedState.get<Boolean>(KEY_COMMENT_FAILED_ONCE) ?: false,
            )
        }
    )
    val sheet: StateFlow<ActionSheet?> = _sheet.asStateFlow()

    private val _commentDraft = MutableStateFlow(savedState.get<String>(KEY_COMMENT_DRAFT) ?: "")
    /** Kept when the sheet is closed or a post fails; cleared only once a comment is posted. */
    val commentDraft: StateFlow<String> = _commentDraft.asStateFlow()

    /** Only this work's entry, the sign-in state and this work's running actions: other works never emit. */
    val actions: StateFlow<ActionsUi> = run {
        val src = actionSource
        val r = runner
        if (src == null || r == null) return@run MutableStateFlow(ActionsUi()).asStateFlow()
        combine(
            src.actingAccount.map { it != null }.distinctUntilChanged(),
            src.state.map { it[workId] }.distinctUntilChanged(),
            r.running.map { runningFor(it) }.distinctUntilChanged(),
            ::actionsUi,
        ).stateIn(
            viewModelScope, SharingStarted.Eagerly,
            actionsUi(src.actingAccount.value != null, src.state.value[workId], runningFor(r.running.value)),
        )
    }

    private fun runningFor(keys: Set<String>): Set<WorkAction> =
        WorkAction.entries.filterTo(HashSet()) { ActionRunner.workKey(workId, it.name) in keys }

    private fun actionsUi(signedIn: Boolean, a: WorkActions?, running: Set<WorkAction>) = ActionsUi(
        signedIn = signedIn,
        known = a != null,
        kudosGiven = a?.kudosGiven == true,
        bookmarked = a?.bookmarked == true,
        markedForLater = a?.markedForLater == true,
        subscribed = a?.subscribed == true,
        available = if (a == null) WorkAction.entries.toSet() else buildSet {
            if (a.kudos != null || a.kudosGiven) add(WorkAction.KUDOS)
            if (a.bookmarkForm != null || a.bookmarkEditUrl != null) add(WorkAction.BOOKMARK)
            if (a.markForLater != null) add(WorkAction.LATER)
            if (a.subscribe != null) add(WorkAction.SUBSCRIBE)
            if (a.comment != null) add(WorkAction.COMMENT)
        },
        running = running,
    )

    /**
     * Runs [block] in the app scope (one per action at a time; a tap while it runs is ignored, so a
     * repository `Ok(pending)` is never shown as confirmed) and reports the result here if the reader
     * is still open. [onError] returns true when it showed the failure itself (inside an open sheet,
     * where a snackbar would be hidden under the sheet's scrim); otherwise a snackbar says it.
     */
    private fun <T> runAction(
        action: WorkAction,
        block: suspend (WorkActionSource) -> Loaded<T>,
        onError: (ActionMessage, Loaded.Error) -> Boolean = { _, _ -> false },
        onOk: (T) -> Unit = {},
    ) {
        val src = actionSource ?: return
        val r = runner ?: return
        if (src.actingAccount.value == null) return
        val job = r.launch(ActionRunner.workKey(workId, action.name)) { block(src) } ?: return
        viewModelScope.launch {
            val res = try {
                job.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Loaded.Error(FetchResult.Failed(e))
            }
            val msg = actionFeedback(action, res)
            when (res) {
                is Loaded.Ok -> { onOk(res.value); _messages.trySend(msg) }
                is Loaded.Error -> if (!onError(msg, res)) _messages.trySend(msg)
            }
        }
    }

    /**
     * Not a toggle: nothing happens once kudos are given. While the state is unknown AO3 is read first,
     * so kudos it already shows read "Kudos already given" (Ok(false)), not "Kudos sent" (Ok(true)).
     */
    fun kudos() {
        if (actions.value.kudosGiven) return
        runAction(WorkAction.KUDOS, { src ->
            val known = src.state.value[workId] ?: when (val fresh = src.refresh(workId)) {
                is Loaded.Error -> return@runAction fresh
                is Loaded.Ok -> fresh.value
            }
            if (known.kudosGiven) return@runAction Loaded.Ok(false)
            when (val r = src.kudos(workId)) {
                is Loaded.Ok -> Loaded.Ok(true)
                is Loaded.Error -> r
            }
        })
    }

    fun toggleLater() = toggle(WorkAction.LATER, { it.markedForLater }) { it.toggleMarkForLater(workId) }

    fun toggleSubscribe() = toggle(WorkAction.SUBSCRIBE, { it.subscribed }) { it.toggleSubscribe(workId) }

    /**
     * The owner means the opposite of what the button showed when tapped. While AO3's state is unknown
     * (shown as "not done") it is read first. If the state (read, or updated since the tap) already is
     * what the owner meant, nothing is sent: a tap on Subscribe can never unsubscribe.
     */
    private fun toggle(action: WorkAction, get: (WorkActions) -> Boolean, call: suspend (WorkActionSource) -> Loaded<Boolean>) {
        val intended = !(actionSource?.state?.value?.get(workId)?.let(get) ?: false)
        runAction(action, { src ->
            val now = src.state.value[workId] ?: when (val fresh = src.refresh(workId)) {
                is Loaded.Error -> return@runAction fresh
                is Loaded.Ok -> fresh.value
            }
            if (get(now) == intended) return@runAction Loaded.Ok(intended)
            call(src)
        })
    }

    /** Opens the bookmark sheet, pre-filled from the existing bookmark (read from AO3 first when needed). */
    fun openBookmark() {
        val src = actionSource ?: return
        if (src.actingAccount.value == null || _sheet.value != null) return
        val existing = src.existingBookmark(workId)
        val known = src.state.value[workId]
        when {
            existing != null -> _sheet.value = ActionSheet.Bookmark(false, existing, existing = true)
            known != null && !known.bookmarked -> _sheet.value = ActionSheet.Bookmark(false, EMPTY_BOOKMARK, existing = false)
            else -> {
                _sheet.value = ActionSheet.Bookmark(true, EMPTY_BOOKMARK, existing = known?.bookmarked == true)
                viewModelScope.launch {
                    val r = src.refresh(workId)
                    val ex = src.existingBookmark(workId)
                    val now = src.state.value[workId]
                    val bookmarked = now?.bookmarked == true
                    val note = when {
                        now == null && r is Loaded.Error -> "Couldn't check AO3 for an existing bookmark. Saving replaces its notes and tags."
                        bookmarked && ex == null -> "Couldn't load this bookmark's notes and tags. Saving replaces them."
                        else -> null
                    }
                    _sheet.update {
                        if (it is ActionSheet.Bookmark && it.loading) ActionSheet.Bookmark(false, ex ?: EMPTY_BOOKMARK, bookmarked, note) else it
                    }
                }
            }
        }
    }

    /** Creates or edits the bookmark; the sheet closes only once AO3 confirms it. */
    fun saveBookmark(input: BookmarkInput) {
        _sheet.update { s -> if (s is ActionSheet.Bookmark) s.copy(error = null) else s }
        runAction(
            WorkAction.BOOKMARK, { it.saveBookmark(workId, input) },
            onError = { msg, _ -> showInSheet { s -> (s as? ActionSheet.Bookmark)?.copy(error = msg.text) } },
        ) {
            _sheet.update { s -> if (s is ActionSheet.Bookmark) null else s }
        }
    }

    /** Puts a failure into the open sheet; false (a snackbar then says it) when that sheet is gone. */
    private fun showInSheet(change: (ActionSheet) -> ActionSheet?): Boolean {
        var shown = false
        _sheet.update { s -> s?.let(change)?.also { shown = true } ?: s }
        return shown
    }

    /** Opens the comment sheet for the chapter shown now. */
    fun openComment() {
        val src = actionSource ?: return
        if (src.actingAccount.value == null || _sheet.value != null) return
        val s = _state.value
        val ref = s.chapters.getOrNull(s.index) ?: s.content?.ref ?: return
        setSheet(commentSheet(ref.chapterId, ref.number, commentMustCheck, commentFailedOnce))
    }

    /** A reopened or restored sheet under the guard says why Post is off (the original error is gone). */
    private fun commentSheet(chapterId: Long?, number: Int, mustCheck: Boolean, failedOnce: Boolean) =
        ActionSheet.Comment(
            chapterId, number,
            error = if (mustCheck) COMMENT_MAY_NOT_HAVE_POSTED else null,
            mustCheck = mustCheck, failedOnce = failedOnce,
        )

    /**
     * The guard after an uncertain post, kept with the draft (savedState): closing and reopening the
     * sheet, or process death, still shows Post off until the text is edited or [postAgain].
     */
    private var commentMustCheck: Boolean
        get() = savedState.get<Boolean>(KEY_COMMENT_MUST_CHECK) ?: false
        set(v) { savedState[KEY_COMMENT_MUST_CHECK] = v }
    /** A post of this draft failed: the button reads "Post again". */
    private var commentFailedOnce: Boolean
        get() = savedState.get<Boolean>(KEY_COMMENT_FAILED_ONCE) ?: false
        set(v) { savedState[KEY_COMMENT_FAILED_ONCE] = v }

    /** Editing the text after an uncertain post is the owner's own change: Post is enabled again. */
    fun setCommentDraft(text: String) {
        if (text != _commentDraft.value) {
            commentMustCheck = false
            _sheet.update { s -> if (s is ActionSheet.Comment && s.mustCheck) s.copy(mustCheck = false) else s }
        }
        _commentDraft.value = text
        savedState[KEY_COMMENT_DRAFT] = text
    }

    /**
     * Posts the draft once (never retried). On failure the text stays and the error shows in the sheet;
     * after a post that may have gone through, Post stays off until the text is edited or [postAgain].
     */
    fun postComment() {
        val target = _sheet.value as? ActionSheet.Comment ?: return
        if (target.mustCheck || commentMustCheck) return
        val text = _commentDraft.value
        if (text.isBlank()) return
        _sheet.update { s -> if (s is ActionSheet.Comment) s.copy(error = null) else s }
        runAction(
            WorkAction.COMMENT, { it.comment(workId, target.chapterId, text) },
            onError = { msg, err ->
                val cause = (err.result as? FetchResult.Failed)?.cause
                val uncertain = cause is ActionUnconfirmedException || cause is ActionMayHaveTakenEffectException
                // Kept even if the sheet was closed meanwhile: reopening it still shows the guard.
                if (_commentDraft.value == text) {
                    commentFailedOnce = true
                    if (uncertain) commentMustCheck = true
                }
                showInSheet { s ->
                    (s as? ActionSheet.Comment)?.takeIf { it.chapterId == target.chapterId }
                        ?.copy(error = msg.text, mustCheck = uncertain, failedOnce = true)
                }
            },
        ) {
            _commentDraft.update { if (it == text) "" else it }
            if (_commentDraft.value.isEmpty()) {
                savedState.remove<String>(KEY_COMMENT_DRAFT)
                commentMustCheck = false
                commentFailedOnce = false
            }
            if ((_sheet.value as? ActionSheet.Comment)?.chapterId == target.chapterId) setSheet(null)
        }
    }

    /** The owner checked the work after an uncertain post and still wants to post. */
    fun postAgain() {
        if (_commentDraft.value.isBlank()) return
        commentMustCheck = false
        _sheet.update { s -> if (s is ActionSheet.Comment) s.copy(mustCheck = false) else s }
        postComment()
    }

    fun closeSheet() = setSheet(null)

    private fun setSheet(s: ActionSheet?) {
        _sheet.value = s
        if (s is ActionSheet.Comment) {
            savedState[KEY_COMMENT_NUMBER] = s.chapterNumber
            savedState[KEY_COMMENT_CHAPTER] = s.chapterId?.toString()
        } else {
            savedState.remove<Int>(KEY_COMMENT_NUMBER)
            savedState.remove<String>(KEY_COMMENT_CHAPTER)
        }
    }

    fun setFontSize(sp: Int) {
        val clamped = sp.coerceIn(MIN_FONT_SP, MAX_FONT_SP)
        _state.update { it.copy(fontSizeSp = clamped) }
        fontWrites++
        viewModelScope.launch { try { settings.setFontSize(clamped) } finally { fontWrites-- } }
    }

    fun setFont(f: ReaderFont) {
        _state.update { it.copy(font = f) }
        fontWrites++
        viewModelScope.launch { try { settings.setFont(f) } finally { fontWrites-- } }
    }

    companion object {
        const val SAVE_DEBOUNCE_MS = 500L
        const val SPINNER_DELAY_MS = 350L
        /** How long a downloaded work whose resume point isn't stored waits for the online chapter list. */
        const val LIST_UPDATE_WAIT_MS = 8_000L
        const val MIN_FONT_SP = 14
        const val MAX_FONT_SP = 28
        val EMPTY_BOOKMARK = BookmarkInput("", emptyList(), isPrivate = false, isRec = false)
        private const val KEY_COMMENT_DRAFT = "action.commentDraft"
        private const val KEY_COMMENT_NUMBER = "action.commentNumber"
        private const val KEY_COMMENT_CHAPTER = "action.commentChapterId"
        private const val KEY_COMMENT_MUST_CHECK = "action.commentMustCheck"
        private const val KEY_COMMENT_FAILED_ONCE = "action.commentFailedOnce"

        fun factory(container: AppContainer, workId: Long, chapterId: Long?, number: Int): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    ReaderViewModel(
                        workId, chapterId, number, container.chapterSource, container.db.progressDao(), container.settings,
                        savedState = createSavedStateHandle(),
                        actionSource = container.actionRepo, runner = container.actionRunner,
                        downloadState = container.downloadRepo.observe(workId),
                        requestDownload = container.downloadRepo::request,
                    )
                }
            }
    }
}

/** Chapters are matched by id, or by number when the id is unknown; never by title. */
internal fun ChapterRef.matches(chapterId: Long?, number: Int): Boolean =
    if (chapterId != null && this.chapterId != null) this.chapterId == chapterId else this.number == number
