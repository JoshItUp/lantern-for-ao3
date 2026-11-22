package app.ao3reader.ui.library

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.ao3reader.AppContainer
import app.ao3reader.account.Session
import app.ao3reader.ao3.model.Block
import app.ao3reader.ao3.model.WorkBlurb
import app.ao3reader.ao3.net.FetchResult
import app.ao3reader.ao3.parse.Ao3Urls
import app.ao3reader.ao3.parse.BookmarkExtra
import app.ao3reader.ao3.parse.LibraryPage
import app.ao3reader.ao3.parse.ReadingExtra
import app.ao3reader.ao3.parse.Subscription
import app.ao3reader.ao3.parse.SubscriptionPage
import app.ao3reader.data.LibraryRepository
import app.ao3reader.data.LibrarySection
import app.ao3reader.data.Loaded
import app.ao3reader.data.db.ProgressDao
import app.ao3reader.data.db.ProgressEntity
import app.ao3reader.data.db.ReadMarkDao
import app.ao3reader.ui.common.CardLinks
import app.ao3reader.ui.common.cardLinks
import app.ao3reader.ui.search.SearchViewModel
import app.ao3reader.ui.search.errorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl

/** What the library screen needs from [LibraryRepository] and the session (a seam for tests). */
interface LibrarySource {
    /** The acting account (lowercased; see [Session.actingAccount]), or null when there is none. */
    val account: Flow<String?>
    fun cached(section: LibrarySection, page: Int): Flow<LibraryPage?>
    suspend fun load(section: LibrarySection, page: Int): Loaded<LibraryPage>
    suspend fun subscriptions(page: Int): Loaded<SubscriptionPage>
}

fun LibraryRepository.asLibrarySource(session: Session): LibrarySource {
    val repo = this
    return object : LibrarySource {
        override val account: Flow<String?> = session.actingAccount.map { it?.lowercase() }.distinctUntilChanged()
        override fun cached(section: LibrarySection, page: Int) = repo.cached(section, page)
        override suspend fun load(section: LibrarySection, page: Int) = repo.load(section, page)
        override suspend fun subscriptions(page: Int) = repo.subscriptions(page)
    }
}

/** Library-only lines under a card, built once in the VM. */
@Immutable
data class CardExtras(
    /** "Visited 12 Nov 2026 · 3 visits" (History, Later). */
    val visited: String? = null,
    val updateAvailable: Boolean = false,
    /** The owner's bookmark notes. */
    val notes: List<Block> = emptyList(),
    val tags: List<String> = emptyList(),
    /** "Private", "Rec". */
    val labels: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = visited == null && !updateAvailable && notes.isEmpty() && tags.isEmpty() && labels.isEmpty()
}

/**
 * One compact subscription row. [workId] set: opens the reader. [url] set (series/author, AO3 https only):
 * opens the in-app work list. Neither: not tappable.
 */
@Immutable
data class SubRow(
    val key: String, val title: String, val detail: String?, val workId: Long?, val url: HttpUrl?,
    /** The work list's screen title ("Works by …" for an author). */
    val listTitle: String = title,
)

@Immutable
data class SectionState(
    val works: List<WorkBlurb> = emptyList(),
    val summaries: Map<Long, List<Block>> = emptyMap(),
    /** Author and series links per work, built with the summaries. */
    val links: Map<Long, CardLinks> = emptyMap(),
    val extras: Map<Long, CardExtras> = emptyMap(),
    val subs: List<SubRow> = emptyList(),
    /** Last page shown (0: nothing yet). */
    val page: Int = 0,
    val hasNext: Boolean = false,
    /** A request for this section is in flight (page 1 or a next page). */
    val loading: Boolean = false,
    /** The in-flight request is page 1 (first load or refresh); shown content stays. */
    val refreshing: Boolean = false,
    /** The refresh was started by pull-to-refresh (its own indicator shows instead of the bar). */
    val pulled: Boolean = false,
    val error: FetchResult? = null,
    /** Page whose load failed, so retry repeats the right request. */
    val failedPage: Int? = null,
    /** Page 1 came from the network at least once: an empty list really is empty. */
    val loaded: Boolean = false,
    /** Bumped whenever page 1 replaces the list (cache or network), so the screen can show new top items. */
    val replaceId: Int = 0,
) {
    val isEmpty: Boolean get() = works.isEmpty() && subs.isEmpty()
}

/** A Library tab: one of AO3's paged sections (signed in only), or the on-device downloads. */
sealed interface LibraryTab {
    data class Ao3(val section: LibrarySection) : LibraryTab
    data object Downloads : LibraryTab
}

/** The tab row's order: the four AO3 sections, then Downloads last. */
val LIBRARY_TABS: List<LibraryTab> = LibrarySection.entries.map { LibraryTab.Ao3(it) } + LibraryTab.Downloads

@Immutable
data class LibraryUiState(
    val signedIn: Boolean = false,
    val selected: LibraryTab = LibraryTab.Ao3(LibrarySection.HISTORY),
    val sections: Map<LibrarySection, SectionState> = FRESH,
    /** Progress of shown and downloaded works. */
    val progressByWork: Map<Long, ProgressEntity> = emptyMap(),
    val readMarks: Set<Long> = emptySet(),
    /** Works with a stored download (the card's "⤓"). */
    val downloadedIds: Set<Long> = emptySet(),
)

private val FRESH: Map<LibrarySection, SectionState> = LibrarySection.entries.associateWith { SectionState() }

const val SIGN_IN_TO_SEE_LIBRARY = "Sign in to see your library"

/**
 * Search's error copy. Errors only show while an account acts (otherwise the sign-in prompt shows), so
 * a NotSignedIn result there is a failed silent re-login: a plain, retryable error.
 */
fun libraryErrorMessage(e: FetchResult): String = errorMessage(e)

internal fun visitedLine(r: ReadingExtra?): String? {
    if (r == null) return null
    val parts = buildList {
        r.lastVisited?.let { add("Visited $it") }
        r.visits?.let { add(if (it == 1) "1 visit" else "$it visits") }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/**
 * The Library's tab selection and its four AO3 tabs (the Downloads list has its own [DownloadsViewModel]).
 * Each section keeps its own pages, so switching tabs is instant. A section loads the first time it is
 * shown: its cached page 1 first, then page 1 from AO3 once. Paging works like Search: one request at a
 * time per section, appended, deduped by work.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val source: LibrarySource,
    private val progressDao: ProgressDao,
    private val readMarkDao: ReadMarkDao,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val downloadedIds: Flow<Set<Long>> = flowOf(emptySet()),
) : ViewModel() {
    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    /** Per section: bumped to discard a load in flight (refresh, account change). */
    private val generations = IntArray(LibrarySection.entries.size)
    private val jobs = arrayOfNulls<Job>(LibrarySection.entries.size)
    private val started = HashSet<LibrarySection>()
    /** The account whose lists are held; a lapse and return of the same account keeps them. */
    private var shownAccount: String? = null
    /** The first account value picks the opening tab: Downloads when signed out. Later changes keep the tab. */
    private var tabChosen = false

    init {
        viewModelScope.launch {
            downloadedIds.distinctUntilChanged().collect { ids -> _state.update { it.copy(downloadedIds = ids) } }
        }
        viewModelScope.launch {
            source.account.distinctUntilChanged().collect { account ->
                if (!tabChosen) {
                    tabChosen = true
                    if (account == null) _state.update { it.copy(selected = LibraryTab.Downloads) }
                }
                if (account != shownAccount) {
                    // Another account, or none (signed out, or stored credentials rejected): nothing
                    // shown or in flight belongs to it. Signing back in starts the tabs afresh.
                    shownAccount = account
                    LibrarySection.entries.forEach(::discard)
                    started.clear()
                    _state.update { it.copy(signedIn = account != null, sections = FRESH) }
                }
                val tab = _state.value.selected
                if (account != null && tab is LibraryTab.Ao3) ensureStarted(tab.section)
            }
        }
        viewModelScope.launch {
            _state.map { s -> shownWorkIds(s) }
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

    private fun shownWorkIds(s: LibraryUiState): List<Long> {
        val ids = LinkedHashSet<Long>()
        for (sec in s.sections.values) {
            sec.works.mapTo(ids) { it.workId }
            sec.subs.mapNotNullTo(ids) { it.workId }
        }
        // The Downloads tab opens works at their reading position too.
        ids += s.downloadedIds
        return ids.sorted()
    }

    fun select(tab: LibraryTab) {
        tabChosen = true
        _state.update { it.copy(selected = tab) }
        if (_state.value.signedIn && tab is LibraryTab.Ao3) ensureStarted(tab.section)
    }

    /** Page 1 again (pull-to-refresh); shown content stays until it arrives. */
    fun refresh(section: LibrarySection, pulled: Boolean = true) {
        if (!_state.value.signedIn) return
        val s = section(section)
        if (s.refreshing) return
        started += section
        val gen = discard(section)
        updateSection(section) { it.copy(loading = true, refreshing = true, pulled = pulled, error = null, failedPage = null) }
        launchLoad(section, gen) { fetch(section, 1, gen) }
    }

    fun loadNextPage(section: LibrarySection) {
        if (!_state.value.signedIn) return
        val s = section(section)
        if (s.loading || !s.hasNext || s.error != null || s.page == 0) return
        startNext(section, s.page + 1)
    }

    /** Repeats the request that failed (page 1 or a next page). */
    fun retry(section: LibrarySection) {
        if (!_state.value.signedIn) return
        val s = section(section)
        val p = s.failedPage ?: return
        if (s.loading) return
        if (p <= 1) refresh(section, pulled = false) else startNext(section, p)
    }

    private fun ensureStarted(section: LibrarySection) {
        if (!started.add(section)) return
        val gen = discard(section)
        updateSection(section) { it.copy(loading = true, refreshing = true, pulled = false, error = null, failedPage = null) }
        launchLoad(section, gen) {
            if (section != LibrarySection.SUBSCRIPTIONS) {
                val cached = withContext(parseDispatcher) {
                    source.cached(section, 1).first()?.let { prepare(section, it, emptySet()) }
                }
                if (cached != null && gen == generations[section.ordinal] && section(section).isEmpty) {
                    updateSection(section) {
                        it.copy(works = cached.works, summaries = cached.summaries, links = cached.links, extras = cached.extras,
                            page = 1, hasNext = cached.hasNext, replaceId = it.replaceId + 1)
                    }
                }
            }
            fetch(section, 1, gen)
        }
    }

    private fun startNext(section: LibrarySection, page: Int) {
        val gen = generations[section.ordinal]
        updateSection(section) { it.copy(loading = true, refreshing = false, pulled = false, error = null, failedPage = null) }
        launchLoad(section, gen) { fetch(section, page, gen) }
    }

    /** Never leaves [SectionState.loading] stuck: anything unexpected becomes [FetchResult.Failed]. */
    private fun launchLoad(section: LibrarySection, gen: Int, block: suspend () -> Unit) {
        jobs[section.ordinal] = viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(section, gen, (section(section).let { if (it.refreshing) 1 else it.page + 1 }), FetchResult.Failed(e))
            }
        }
    }

    private suspend fun fetch(section: LibrarySection, page: Int, gen: Int) {
        val replace = page == 1
        val have = if (replace) emptySet() else section(section).works.mapTo(HashSet()) { it.workId }
        val result: Loaded<Prepared> = if (section == LibrarySection.SUBSCRIPTIONS) {
            when (val r = source.subscriptions(page)) {
                is Loaded.Ok -> Loaded.Ok(prepareSubs(r.value, if (replace) emptySet() else section(section).subs.mapTo(HashSet()) { it.key }))
                is Loaded.Error -> r
            }
        } else {
            when (val r = source.load(section, page)) {
                is Loaded.Ok -> Loaded.Ok(withContext(parseDispatcher) { prepare(section, r.value, have) })
                is Loaded.Error -> r
            }
        }
        if (gen != generations[section.ordinal]) return
        when (result) {
            is Loaded.Ok -> {
                val p = result.value
                updateSection(section) { s ->
                    val base = if (replace) SectionState(replaceId = s.replaceId + 1) else s
                    base.copy(
                        works = base.works + p.works, summaries = base.summaries + p.summaries, links = base.links + p.links,
                        extras = base.extras + p.extras, subs = base.subs + p.subs,
                        page = page, hasNext = p.hasNext, loaded = true,
                        loading = false, refreshing = false, pulled = false, error = null, failedPage = null,
                    )
                }
            }
            is Loaded.Error -> fail(section, gen, page, result.result)
        }
    }

    private fun fail(section: LibrarySection, gen: Int, page: Int, e: FetchResult) {
        if (gen != generations[section.ordinal]) return
        updateSection(section) {
            it.copy(loading = false, refreshing = false, pulled = false, error = e, failedPage = page)
        }
    }

    /** Cancels the section's load in flight and returns the new generation. */
    private fun discard(section: LibrarySection): Int {
        jobs[section.ordinal]?.cancel()
        jobs[section.ordinal] = null
        return ++generations[section.ordinal]
    }

    private fun section(section: LibrarySection): SectionState = _state.value.sections.getValue(section)

    private inline fun updateSection(section: LibrarySection, crossinline f: (SectionState) -> SectionState) {
        _state.update { s -> s.copy(sections = s.sections + (section to f(s.sections.getValue(section)))) }
    }

    private class Prepared(
        val works: List<WorkBlurb> = emptyList(),
        val summaries: Map<Long, List<Block>> = emptyMap(),
        val links: Map<Long, CardLinks> = emptyMap(),
        val extras: Map<Long, CardExtras> = emptyMap(),
        val subs: List<SubRow> = emptyList(),
        val hasNext: Boolean,
    )

    /** Off the main thread: dedupes against [have] and converts summaries, notes and extras once. */
    private fun prepare(section: LibrarySection, page: LibraryPage, have: Set<Long>): Prepared {
        val seen = HashSet(have)
        // AO3 listings can shift between requests; never show a work twice (LazyColumn keys).
        val works = page.works.filter { seen.add(it.workId) }
        val summaries = HashMap<Long, List<Block>>(works.size)
        val links = HashMap<Long, CardLinks>(works.size)
        val extras = HashMap<Long, CardExtras>()
        for (w in works) {
            summaries[w.workId] = SearchViewModel.convertSummary(w.summaryHtml)
            links[w.workId] = w.cardLinks()
            val e = when (section) {
                LibrarySection.BOOKMARKS -> bookmarkExtras(page.bookmarks[w.workId])
                else -> readingExtras(page.readings[w.workId])
            }
            if (e != null && !e.isEmpty) extras[w.workId] = e
        }
        return Prepared(works, summaries, links, extras, hasNext = page.hasNext)
    }

    private fun readingExtras(r: ReadingExtra?): CardExtras? =
        r?.let { CardExtras(visited = visitedLine(it), updateAvailable = it.updateAvailable) }

    private fun bookmarkExtras(b: BookmarkExtra?): CardExtras? = b?.let {
        CardExtras(
            notes = SearchViewModel.convertSummary(it.notesHtml),
            tags = it.tags,
            labels = buildList { if (it.isPrivate) add("Private"); if (it.isRec) add("Rec") },
        )
    }

    private fun prepareSubs(page: SubscriptionPage, have: Set<String>): Prepared {
        val seen = HashSet(have)
        val rows = page.items.mapNotNull { item ->
            val row = when (item) {
                is Subscription.Work -> SubRow("work:${item.workId}", item.title, item.author, item.workId, null)
                is Subscription.Series -> item.url.toString().let { SubRow(it, item.title, "Series", null, Ao3Urls.workList(item.url)) }
                is Subscription.User -> item.url.toString().let { SubRow(it, item.title, "Author", null, Ao3Urls.workList(item.url), "Works by ${item.title}") }
            }
            row.takeIf { seen.add(it.key) }
        }
        return Prepared(subs = rows, hasNext = page.hasNext)
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                LibraryViewModel(
                    container.libraryRepo.asLibrarySource(container.session),
                    container.db.progressDao(),
                    container.db.readMarkDao(),
                    downloadedIds = container.downloadRepo.downloadedIds(),
                )
            }
        }
    }
}
