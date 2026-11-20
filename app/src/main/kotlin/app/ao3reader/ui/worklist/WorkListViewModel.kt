package app.ao3reader.ui.worklist

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.ao3reader.AppContainer
import app.ao3reader.ao3.model.Block
import app.ao3reader.ao3.model.WorkBlurb
import app.ao3reader.ao3.net.FetchResult
import app.ao3reader.ao3.parse.Ao3Urls
import app.ao3reader.ao3.parse.WorkListPage
import app.ao3reader.data.Loaded
import app.ao3reader.data.WorkListSource
import app.ao3reader.data.db.ProgressDao
import app.ao3reader.data.db.ProgressEntity
import app.ao3reader.data.db.ReadMarkDao
import app.ao3reader.ui.common.CardLinks
import app.ao3reader.ui.common.cardLinks
import app.ao3reader.ui.search.SearchViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl

@Immutable
data class WorkListUiState(
    val title: String = "",
    /** The navigation argument was not an AO3 series/author list: nothing is loaded. */
    val invalid: Boolean = false,
    val works: List<WorkBlurb> = emptyList(),
    /** Per work, converted once off the main thread. */
    val summaries: Map<Long, List<Block>> = emptyMap(),
    val links: Map<Long, CardLinks> = emptyMap(),
    /** Last page shown (0: nothing yet). */
    val page: Int = 0,
    val hasNext: Boolean = false,
    val loading: Boolean = false,
    val error: FetchResult? = null,
    /** Page whose load failed, so retry repeats the right request. */
    val failedPage: Int? = null,
    /** Page 1 arrived: an empty list really is empty. */
    val loaded: Boolean = false,
    val progressByWork: Map<Long, ProgressEntity> = emptyMap(),
    val readMarks: Set<Long> = emptySet(),
)

/**
 * A series' or author's works (in AO3's order: series order for a series). Paging works like Search:
 * one request at a time, appended, deduped by work; an error keeps the works shown.
 * [rawUrl] is the decoded navigation argument, validated again here: only AO3 list URLs load.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkListViewModel(
    rawUrl: String?,
    title: String,
    private val source: WorkListSource,
    private val progressDao: ProgressDao,
    private val readMarkDao: ReadMarkDao,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val url: HttpUrl? = Ao3Urls.workListOrNull(rawUrl)
    private val _state = MutableStateFlow(WorkListUiState(title = title, invalid = url == null))
    val state: StateFlow<WorkListUiState> = _state.asStateFlow()

    private var generation = 0
    private var job: Job? = null

    init {
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
        if (url != null) start(1)
    }

    fun loadNextPage() {
        val s = _state.value
        if (url == null || s.loading || !s.hasNext || s.error != null || s.page == 0) return
        start(s.page + 1)
    }

    /** Repeats the request that failed (page 1 or a next page). */
    fun retry() {
        val s = _state.value
        val p = s.failedPage ?: return
        if (url == null || s.loading) return
        start(p)
    }

    private fun start(page: Int) {
        val list = url ?: return
        job?.cancel()
        val gen = ++generation
        _state.update { it.copy(loading = true, error = null, failedPage = null) }
        job = viewModelScope.launch {
            try {
                fetch(list, page, gen)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(gen, page, FetchResult.Failed(e))
            }
        }
    }

    private suspend fun fetch(list: HttpUrl, page: Int, gen: Int) {
        when (val r = source.load(list, page)) {
            is Loaded.Ok -> {
                val have = if (page == 1) emptySet() else _state.value.works.mapTo(HashSet()) { it.workId }
                val p = withContext(parseDispatcher) { prepare(r.value, have, list) }
                if (gen != generation) return
                _state.update { s ->
                    val base = if (page == 1) s.copy(works = emptyList(), summaries = emptyMap(), links = emptyMap()) else s
                    base.copy(
                        title = base.title.ifBlank { r.value.seriesTitle.orEmpty() },
                        works = base.works + p.works, summaries = base.summaries + p.summaries, links = base.links + p.links,
                        page = page, hasNext = r.value.page.hasNext, loaded = true,
                        loading = false, error = null, failedPage = null,
                    )
                }
            }
            is Loaded.Error -> fail(gen, page, r.result)
        }
    }

    private fun fail(gen: Int, page: Int, e: FetchResult) {
        if (gen != generation) return
        _state.update { it.copy(loading = false, error = e, failedPage = page) }
    }

    private class Prepared(val works: List<WorkBlurb>, val summaries: Map<Long, List<Block>>, val links: Map<Long, CardLinks>)

    /** Off the main thread: dedupes (AO3 listings can shift between requests) and builds summaries and links once. */
    private fun prepare(page: WorkListPage, have: Set<Long>, list: HttpUrl): Prepared {
        val seen = HashSet(have)
        val works = page.page.works.filter { seen.add(it.workId) }
        return Prepared(
            works,
            works.associate { it.workId to SearchViewModel.convertSummary(it.summaryHtml) },
            works.associate { it.workId to it.cardLinks(current = list) },
        )
    }

    companion object {
        fun factory(container: AppContainer, rawUrl: String?, title: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                WorkListViewModel(
                    rawUrl, title, container.workListRepo,
                    container.db.progressDao(), container.db.readMarkDao(),
                )
            }
        }
    }
}
