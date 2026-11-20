package app.ao3reader.ui.reader

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
)

/**
 * One chapter in memory at a time: changing chapter replaces [ReaderUiState.content]; the next
 * chapter is only prefetched to the disk cache.
 */
class ReaderViewModel(
    private val workId: Long,
    private val routeChapterId: Long?,
    private val routeNumber: Int,
    private val source: ChapterSource,
    private val progressDao: ProgressDao,
    private val settings: SettingsStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow(ReaderUiState(workId))
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    /** Latest progress for this work, as last read from or written to the database. */
    private var saved: ProgressEntity? = null
    private var loadJob: Job? = null
    private var saveJob: Job? = null
    /** Scroll position reported but not yet written, captured with the chapter it belongs to. */
    private var pending: ProgressEntity? = null
    /** Font writes in flight; settings emissions are ignored meanwhile so a slider drag doesn't jump back. */
    private var fontWrites = 0

    init {
        viewModelScope.launch {
            settings.fontSizeSp.collect { sp -> if (fontWrites == 0) _state.update { it.copy(fontSizeSp = sp) } }
        }
        viewModelScope.launch {
            settings.fontFamily.collect { f -> if (fontWrites == 0) _state.update { it.copy(font = f) } }
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

    /** Route chapter, then saved progress, then the route number, then the first chapter. */
    private fun startIndex(list: List<ChapterRef>): Int {
        routeChapterId?.let { id -> list.indexOfFirst { it.chapterId == id }.takeIf { it >= 0 }?.let { return it } }
        saved?.let { p -> list.indexOfFirst { it.matches(p.chapterId, p.chapterNumber) }.takeIf { it >= 0 }?.let { return it } }
        return list.indexOfFirst { it.number == routeNumber }.coerceAtLeast(0)
    }

    /** The chapter list failed: load the chapter the route or progress points at, and list just that one. */
    private suspend fun openWithoutList() {
        val p = saved
        val ref = when {
            routeChapterId != null -> ChapterRef(routeChapterId, routeNumber, "")
            p != null -> ChapterRef(p.chapterId, p.chapterNumber, "")
            else -> ChapterRef(null, 1, "")
        }
        when (val r = source.chapter(workId, ref)) {
            is Loaded.Ok -> {
                val loadedRef = r.value.ref
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
        loadJob = viewModelScope.launch { loadChapter(index, ref) }
    }

    fun next() = goTo(_state.value.index + 1)

    fun previous() = goTo(_state.value.index - 1)

    fun retry() {
        val s = _state.value
        if (s.loading) return
        if (s.chapters.isEmpty()) {
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

    /** Debounced by [SAVE_DEBOUNCE_MS]; ignored until content is shown and any restore has been applied. */
    fun onScroll(blockIndex: Int, offsetPx: Int) {
        val s = _state.value
        val content = s.content ?: return
        if (s.restore != null) return
        val ref = s.chapters.getOrNull(s.index) ?: content.ref
        pending = ProgressEntity(
            workId = workId,
            workTitle = content.workTitle,
            chapterId = ref.chapterId,
            chapterNumber = ref.number,
            chaptersTotal = s.chapters.size.takeIf { s.chapterListLoaded },
            blockIndex = blockIndex,
            blockOffsetPx = offsetPx,
            updatedAt = 0L,
        )
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            writePending()
        }
    }

    /** Writes any pending position now (called on ON_STOP and before changing chapter). */
    fun flushProgress() {
        saveJob?.cancel()
        if (pending == null) return
        saveJob = viewModelScope.launch { withContext(NonCancellable) { writePending() } }
    }

    private suspend fun writePending() {
        val entity = pending?.copy(updatedAt = clock()) ?: return
        pending = null
        saved = entity
        progressDao.upsert(entity)
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
        const val MIN_FONT_SP = 14
        const val MAX_FONT_SP = 28

        fun factory(container: AppContainer, workId: Long, chapterId: Long?, number: Int): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    ReaderViewModel(workId, chapterId, number, container.workRepo, container.db.progressDao(), container.settings)
                }
            }
    }
}

/** Chapters are matched by id, or by number when the id is unknown; never by title. */
internal fun ChapterRef.matches(chapterId: Long?, number: Int): Boolean =
    if (chapterId != null && this.chapterId != null) this.chapterId == chapterId else this.number == number
