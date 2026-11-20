package app.ao3reader.ui.search

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.ao3reader.AppContainer
import app.ao3reader.ao3.model.Block
import app.ao3reader.ao3.model.SearchQuery
import app.ao3reader.ao3.model.WorkBlurb
import app.ao3reader.ao3.net.FetchResult
import app.ao3reader.ao3.parse.BlockConverter
import app.ao3reader.data.Loaded
import app.ao3reader.data.SearchSource
import app.ao3reader.data.db.PresetDao
import app.ao3reader.data.db.PresetEntity
import app.ao3reader.data.db.ProgressDao
import app.ao3reader.data.db.ProgressEntity
import app.ao3reader.data.db.ReadMarkDao
import app.ao3reader.data.searchQueryFromJson
import app.ao3reader.data.toJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

@Immutable
data class SearchUiState(
    val query: SearchQuery = SearchQuery(),
    val works: List<WorkBlurb> = emptyList(),
    val page: Int = 0,
    val hasNext: Boolean = false,
    val loading: Boolean = false,
    val error: FetchResult? = null,
    val presets: List<PresetEntity> = emptyList(),
    val activePresetId: Long? = null,
    val continueReading: ProgressEntity? = null,
    val progressByWork: Map<Long, ProgressEntity> = emptyMap(),
    val readMarks: Set<Long> = emptySet(),
    /** Summary of each shown work, converted once off the main thread. */
    val summaries: Map<Long, List<Block>> = emptyMap(),
    /** True once any search has been started; distinguishes "no results" from "not searched yet". */
    val searched: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val source: SearchSource,
    private val presetDao: PresetDao,
    private val progressDao: ProgressDao,
    private val readMarkDao: ReadMarkDao,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    /** The query the shown results belong to; next pages use it, not unapplied edits. */
    private var shownQuery: SearchQuery = SearchQuery()
    /** Bumped by every runSearch; a load whose generation is stale is discarded. */
    private var generation = 0
    private var loadJob: Job? = null
    /** Page whose load failed, so retry() repeats the right request. */
    private var failedPage: Int? = null

    init {
        viewModelScope.launch {
            presetDao.observeAll().collect { list -> _state.update { it.copy(presets = list) } }
        }
        viewModelScope.launch {
            progressDao.observeLatest().collect { p -> _state.update { it.copy(continueReading = p) } }
        }
        viewModelScope.launch {
            _state.map { s -> s.works.map { it.workId } }
                .distinctUntilChanged()
                .flatMapLatest { ids ->
                    if (ids.isEmpty()) flowOf(emptyMap<Long, ProgressEntity>() to emptySet<Long>())
                    else combine(progressDao.observeFor(ids), readMarkDao.observeFor(ids)) { p, m ->
                        p.associateBy { it.workId } to m.mapTo(HashSet()) { it.workId }
                    }
                }
                .distinctUntilChanged()
                .collect { (p, m) -> _state.update { it.copy(progressByWork = p, readMarks = m) } }
        }
    }

    fun setQuery(q: SearchQuery) {
        _state.update { s ->
            val active = s.presets.firstOrNull { it.id == s.activePresetId }
            val stillActive = active != null && runCatching { searchQueryFromJson(active.queryJson) }.getOrNull() == q
            s.copy(query = q, activePresetId = if (stillActive) s.activePresetId else null)
        }
    }

    fun runSearch() {
        loadJob?.cancel()
        val gen = ++generation
        val q = _state.value.query
        shownQuery = q
        failedPage = null
        _state.update { it.copy(works = emptyList(), summaries = emptyMap(), page = 0, hasNext = false,
            loading = true, error = null, searched = true) }
        loadJob = viewModelScope.launch { load(gen, q, 1) }
    }

    fun loadNextPage() {
        val s = _state.value
        if (s.loading || !s.hasNext || s.error != null) return
        startPage(s.page + 1)
    }

    /** Repeats the request that failed (first page or a next page). */
    fun retry() {
        val p = failedPage ?: return
        if (_state.value.loading) return
        if (p <= 1) {
            _state.update { it.copy(query = shownQuery) }
            runSearch()
        } else {
            startPage(p)
        }
    }

    private fun startPage(page: Int) {
        val gen = generation
        val q = shownQuery
        failedPage = null
        _state.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch { load(gen, q, page) }
    }

    private suspend fun load(gen: Int, q: SearchQuery, page: Int) {
        val result = source.search(q, page)
        if (gen != generation) return
        when (result) {
            is Loaded.Ok -> {
                val have = _state.value.works.mapTo(HashSet()) { it.workId }
                // AO3 listings can shift between requests; never show a work twice (LazyColumn keys).
                val fresh = result.value.works.filter { have.add(it.workId) }
                val summaries = withContext(parseDispatcher) {
                    fresh.associate { it.workId to convertSummary(it.summaryHtml) }
                }
                if (gen != generation) return
                _state.update {
                    it.copy(works = it.works + fresh, summaries = it.summaries + summaries,
                        page = page, hasNext = result.value.hasNext, loading = false, error = null)
                }
            }
            is Loaded.Error -> {
                failedPage = page
                _state.update { it.copy(loading = false, error = result.result) }
            }
        }
    }

    fun applyPreset(id: Long) {
        val p = _state.value.presets.firstOrNull { it.id == id } ?: return
        val q = runCatching { searchQueryFromJson(p.queryJson) }.getOrElse { SearchQuery() }
        _state.update { it.copy(query = q, activePresetId = id) }
        runSearch()
    }

    fun savePreset(name: String) {
        val s = _state.value
        val position = (s.presets.maxOfOrNull { it.position } ?: -1) + 1
        viewModelScope.launch {
            val id = presetDao.insert(PresetEntity(name = name.trim(), position = position, queryJson = s.query.toJson()))
            _state.update { if (it.query == s.query) it.copy(activePresetId = id) else it }
        }
    }

    fun renamePreset(id: Long, name: String) {
        val p = _state.value.presets.firstOrNull { it.id == id } ?: return
        viewModelScope.launch { presetDao.update(p.copy(name = name.trim())) }
    }

    fun updatePresetFromCurrent(id: Long) {
        val s = _state.value
        val p = s.presets.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            presetDao.update(p.copy(queryJson = s.query.toJson()))
            _state.update { if (it.query == s.query) it.copy(activePresetId = id) else it }
        }
    }

    fun deletePreset(id: Long) {
        _state.update { if (it.activePresetId == id) it.copy(activePresetId = null) else it }
        viewModelScope.launch { presetDao.delete(id) }
    }

    fun reorderPresets(ids: List<Long>) {
        viewModelScope.launch { presetDao.reorder(ids) }
    }

    /**
     * Suggestions for a tag field. Waits 300 ms first, so a collector restarted on every keystroke
     * (e.g. a LaunchedEffect keyed on the text) only fires a request once typing pauses.
     */
    fun autocomplete(kind: String, term: String): Flow<List<String>> = flow {
        if (term.trim().length < 2) {
            emit(emptyList())
            return@flow
        }
        delay(AUTOCOMPLETE_DEBOUNCE_MS)
        val result = try {
            source.autocomplete(kind, term)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        emit(result)
    }

    companion object {
        const val AUTOCOMPLETE_DEBOUNCE_MS = 300L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SearchViewModel(
                    container.searchRepo,
                    container.db.presetDao(),
                    container.db.progressDao(),
                    container.db.readMarkDao(),
                )
            }
        }

        internal fun convertSummary(html: String?): List<Block> {
            if (html.isNullOrBlank()) return emptyList()
            return try {
                BlockConverter.convert(Jsoup.parseBodyFragment(html).body())
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
