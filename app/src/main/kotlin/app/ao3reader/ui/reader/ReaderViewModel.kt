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
import app.ao3reader.data.ChapterSource
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

/**
 * One chapter in memory at a time: changing chapter replaces [ReaderUiState.content]; the next
 * chapter is only prefetched to the disk cache.
 *
 * The current chapter is written to [savedState] under the route's argument keys, so after process
 * death the restored entry reopens the chapter being read rather than the one it was opened at.
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

        fun factory(container: AppContainer, workId: Long, chapterId: Long?, number: Int): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    ReaderViewModel(
                        workId, chapterId, number, container.workRepo, container.db.progressDao(), container.settings,
                        savedState = createSavedStateHandle(),
                    )
                }
            }
    }
}

/** Chapters are matched by id, or by number when the id is unknown; never by title. */
internal fun ChapterRef.matches(chapterId: Long?, number: Int): Boolean =
    if (chapterId != null && this.chapterId != null) this.chapterId == chapterId else this.number == number
