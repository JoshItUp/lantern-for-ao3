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
import app.ao3reader.data.ActionRunner
import app.ao3reader.data.ListActionSource
import app.ao3reader.data.Loaded
import app.ao3reader.ui.common.ActionMessage
import app.ao3reader.ui.common.WorkAction
import app.ao3reader.ui.common.actionFeedback
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
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
    /** Works with a stored download (the card's "⤓"). */
    val downloadedIds: Set<Long> = emptySet(),
)

/** The header's Subscribe / Unsubscribe button for a series or author. */
@Immutable
data class ListSubscribeUi(
    /** False: no button. */
    val signedIn: Boolean = false,
    /** AO3's state has been read (a series page carries it; an author's is read on the first tap). */
    val known: Boolean = false,
    val subscribed: Boolean = false,
    val running: Boolean = false,
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
    private val listActions: ListActionSource? = null,
    private val runner: ActionRunner? = null,
    private val downloadedIds: Flow<Set<Long>> = flowOf(emptySet()),
) : ViewModel() {
    private val url: HttpUrl? = Ao3Urls.workListOrNull(rawUrl)
    private val _state = MutableStateFlow(WorkListUiState(title = title, invalid = url == null))
    val state: StateFlow<WorkListUiState> = _state.asStateFlow()

    private var generation = 0
    private var job: Job? = null

    private val _messages = Channel<ActionMessage>(Channel.BUFFERED)
    /** One-off feedback for a snackbar. */
    val messages: Flow<ActionMessage> = _messages.receiveAsFlow()

    private val listKey: String? = url?.let { u -> listActions?.listKey(u) }
    private val runKey: String? = listKey?.let { ActionRunner.listKey(it) }

    /** Narrow: this list's entry, the sign-in state and whether its toggle runs. */
    val subscribe: StateFlow<ListSubscribeUi> = run {
        val src = listActions
        val r = runner
        val key = listKey
        if (src == null || r == null || key == null) return@run MutableStateFlow(ListSubscribeUi()).asStateFlow()
        fun ui(signedIn: Boolean, known: Boolean, subscribed: Boolean, running: Boolean) =
            ListSubscribeUi(signedIn, known, subscribed, running)
        combine(
            src.actingAccount.map { it != null }.distinctUntilChanged(),
            src.listState.map { it[key] }.distinctUntilChanged(),
            r.running.map { runKey in it }.distinctUntilChanged(),
        ) { signedIn, l, running -> ui(signedIn, l != null, l?.subscribed == true, running) }
            .stateIn(
                viewModelScope, SharingStarted.Eagerly,
                src.listState.value[key].let { l -> ui(src.actingAccount.value != null, l != null, l?.subscribed == true, runKey in r.running.value) },
            )
    }

    /**
     * Subscribes or unsubscribes in the app scope (a tap while it runs is ignored). Unknown state reads
     * as "not subscribed", so it is read first, and a list already subscribed is left alone.
     */
    fun toggleSubscribe() {
        val src = listActions ?: return
        val list = url ?: return
        val key = listKey ?: return
        val r = runner ?: return
        if (src.actingAccount.value == null) return
        val intended = src.listState.value[key]?.subscribed != true
        val d = r.launch(runKey!!) {
            // Read first when unknown; and if the state (read, or updated since the tap) already is what
            // the owner meant, nothing is sent.
            val now = src.listState.value[key] ?: when (val fresh = src.refreshList(list)) {
                is Loaded.Error -> return@launch fresh
                is Loaded.Ok -> fresh.value
            }
            if (now.subscribed == intended) return@launch Loaded.Ok(intended)
            src.toggleListSubscribe(list)
        } ?: return
        viewModelScope.launch {
            val res: Loaded<Boolean> = try {
                d.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Loaded.Error(FetchResult.Failed(e))
            }
            _messages.trySend(actionFeedback(WorkAction.SUBSCRIBE, res))
        }
    }

    init {
        viewModelScope.launch {
            downloadedIds.distinctUntilChanged().collect { ids -> _state.update { it.copy(downloadedIds = ids) } }
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
                    listActions = container.actionRepo, runner = container.actionRunner,
                    downloadedIds = container.downloadRepo.downloadedIds(),
                )
            }
        }
    }
}
