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
import app.ao3reader.data.ActionRunner
import app.ao3reader.data.ChapterSource
import app.ao3reader.data.WorkActionSource
import app.ao3reader.ui.common.ActionMessage
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
import app.ao3reader.data.db.ProgressDao
import app.ao3reader.data.db.ProgressEntity
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
    /** [loading]: AO3's bookmark is being read first. [note]: a warning shown above Save. */
    data class Bookmark(val loading: Boolean, val initial: BookmarkInput, val existing: Boolean, val note: String? = null) : ActionSheet
    /** The comment goes to this chapter (the one shown when the sheet opened). */
    data class Comment(val chapterId: Long?, val chapterNumber: Int) : ActionSheet
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
        open()
    }

    private fun open() {
        loadJob?.cancel()
        _state.update { it.copy(loading = true, error = null, content = null) }
        loadJob = viewModelScope.launch {
            saved = progressDao.get(workId)
            val list = (source.chapters(workId) as? Loaded.Ok)?.value?.takeIf { it.isNotEmpty() }
            if (list != null) {
                val index = startIndex(list)
                _state.update { it.copy(chapters = list, index = index, chapterListLoaded = true) }
                loadChapter(index, list[index])
            } else {
                openWithoutList()
            }
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

    private val _sheet = MutableStateFlow<ActionSheet?>(null)
    val sheet: StateFlow<ActionSheet?> = _sheet.asStateFlow()

    private val _commentDraft = MutableStateFlow("")
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
     * is still open.
     */
    private fun <T> runAction(action: WorkAction, block: suspend (WorkActionSource) -> Loaded<T>, onOk: (T) -> Unit = {}) {
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
            if (res is Loaded.Ok) onOk(res.value)
            _messages.trySend(actionFeedback(action, res))
        }
    }

    /** Not a toggle: nothing happens once kudos are given. */
    fun kudos() {
        if (actions.value.kudosGiven) return
        runAction(WorkAction.KUDOS, { it.kudos(workId) })
    }

    fun toggleLater() = toggle(WorkAction.LATER, { it.markedForLater }) { it.toggleMarkForLater(workId) }

    fun toggleSubscribe() = toggle(WorkAction.SUBSCRIBE, { it.subscribed }) { it.toggleSubscribe(workId) }

    /**
     * The owner means the opposite of what the button shows. While AO3's state is unknown (shown as
     * "not done") it is read first: if it already is what the owner meant, nothing is sent, so a tap
     * on an unknown Subscribe can never unsubscribe.
     */
    private fun toggle(action: WorkAction, get: (WorkActions) -> Boolean, call: suspend (WorkActionSource) -> Loaded<Boolean>) {
        val intended = !(actionSource?.state?.value?.get(workId)?.let(get) ?: false)
        runAction(action, { src ->
            if (src.state.value[workId] == null) {
                when (val fresh = src.refresh(workId)) {
                    is Loaded.Error -> return@runAction fresh
                    is Loaded.Ok -> if (get(fresh.value) == intended) return@runAction Loaded.Ok(intended)
                }
            }
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
        runAction(WorkAction.BOOKMARK, { it.saveBookmark(workId, input) }) {
            _sheet.update { s -> if (s is ActionSheet.Bookmark) null else s }
        }
    }

    /** Opens the comment sheet for the chapter shown now. */
    fun openComment() {
        val src = actionSource ?: return
        if (src.actingAccount.value == null || _sheet.value != null) return
        val s = _state.value
        val ref = s.chapters.getOrNull(s.index) ?: s.content?.ref ?: return
        _sheet.value = ActionSheet.Comment(ref.chapterId, ref.number)
    }

    fun setCommentDraft(text: String) { _commentDraft.value = text }

    /** Posts the draft once (never retried); on failure the text stays for the owner to check and resend. */
    fun postComment() {
        val target = _sheet.value as? ActionSheet.Comment ?: return
        val text = _commentDraft.value
        if (text.isBlank()) return
        runAction(WorkAction.COMMENT, { it.comment(workId, target.chapterId, text) }) {
            _commentDraft.update { if (it == text) "" else it }
            _sheet.update { if (it == target) null else it }
        }
    }

    fun closeSheet() { _sheet.value = null }

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
        const val MIN_FONT_SP = 14
        const val MAX_FONT_SP = 28
        val EMPTY_BOOKMARK = BookmarkInput("", emptyList(), isPrivate = false, isRec = false)

        fun factory(container: AppContainer, workId: Long, chapterId: Long?, number: Int): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    ReaderViewModel(
                        workId, chapterId, number, container.workRepo, container.db.progressDao(), container.settings,
                        savedState = createSavedStateHandle(),
                        actionSource = container.actionRepo, runner = container.actionRunner,
                    )
                }
            }
    }
}

/** Chapters are matched by id, or by number when the id is unknown; never by title. */
internal fun ChapterRef.matches(chapterId: Long?, number: Int): Boolean =
    if (chapterId != null && this.chapterId != null) this.chapterId == chapterId else this.number == number
