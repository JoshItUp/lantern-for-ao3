package app.ao3reader.ui.worklist

import app.ao3reader.ao3.model.AuthorLink
import app.ao3reader.ao3.model.Block
import app.ao3reader.ao3.model.SeriesLink
import app.ao3reader.ao3.model.WorkBlurb
import app.ao3reader.ao3.model.WorkPage
import app.ao3reader.ao3.net.FetchResult
import app.ao3reader.ao3.parse.WorkListPage
import app.ao3reader.ao3.parse.Ao3Form
import app.ao3reader.ao3.parse.ListActions
import app.ao3reader.data.ActionRunner
import app.ao3reader.data.ListActionSource
import app.ao3reader.data.Loaded
import app.ao3reader.ui.common.ActionMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import app.ao3reader.data.WorkListSource
import app.ao3reader.data.db.ProgressDao
import app.ao3reader.data.db.ProgressEntity
import app.ao3reader.data.db.ReadMarkDao
import app.ao3reader.data.db.ReadMarkEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkListViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var source: FakeSource
    private lateinit var progress: FakeProgressDao
    private lateinit var marks: FakeReadMarkDao
    private val series = "https://archiveofourown.org/series/9"

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        source = FakeSource()
        progress = FakeProgressDao()
        marks = FakeReadMarkDao()
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(url: String? = series, title: String = "Saga") =
        WorkListViewModel(url, title, source, progress, marks, dispatcher)

    private fun test(body: suspend TestScope.() -> Unit) = runTest(dispatcher) { body() }

    // ---- subscribe (Task 11) ----

    private lateinit var lists: FakeLists
    private lateinit var side: CoroutineScope
    private val msgs = mutableListOf<ActionMessage>()

    private fun subTest(body: suspend TestScope.() -> Unit) = test {
        lists = FakeLists()
        side = CoroutineScope(dispatcher + SupervisorJob())
        try { body() } finally { side.cancel() }
    }

    private fun subVm(url: String? = series): WorkListViewModel {
        val vm = WorkListViewModel(url, "Saga", source, progress, marks, dispatcher, lists, ActionRunner(side))
        side.launch { vm.messages.collect { msgs += it } }
        return vm
    }

    private val seriesKey = "https://archiveofourown.org/series/9"
    private val subscribeForm = Ao3Form("https://archiveofourown.org/users/owner/subscriptions".toHttpUrl(), "post", emptyList())

    @Test fun `signed out shows no subscribe button and a tap sends nothing`() = subTest {
        lists.actingAccount.value = null
        val vm = subVm(); advanceUntilIdle()
        assertFalse(vm.subscribe.value.signedIn)
        vm.toggleSubscribe(); advanceUntilIdle()
        assertTrue(lists.calls.isEmpty())
    }

    @Test fun `subscribe toggles the list, shows progress, then confirms`() = subTest {
        lists.listState.value = mapOf(seriesKey to ListActions(subscribeForm, subscribed = false))
        val gate = CompletableDeferred<Unit>().also { lists.gate = it }
        val vm = subVm(); advanceUntilIdle()
        assertTrue(vm.subscribe.value.signedIn)
        assertFalse(vm.subscribe.value.subscribed)
        vm.toggleSubscribe(); runCurrent()
        assertTrue(vm.subscribe.value.running)
        assertTrue(vm.subscribe.value.subscribed)          // optimistic
        vm.toggleSubscribe(); runCurrent()                 // ignored while running
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(listOf("toggle:$series"), lists.calls)
        assertFalse(vm.subscribe.value.running)
        assertEquals(listOf(ActionMessage("Subscribed")), msgs)
    }

    @Test fun `an unknown list is read first and an existing subscription is never undone`() = subTest {
        lists.refreshed = ListActions(subscribeForm, subscribed = true)
        val vm = subVm("https://archiveofourown.org/users/Bob/works"); advanceUntilIdle()
        assertFalse(vm.subscribe.value.subscribed)
        vm.toggleSubscribe(); advanceUntilIdle()
        assertEquals(listOf("refresh:https://archiveofourown.org/users/Bob/works"), lists.calls)
        assertTrue(vm.subscribe.value.subscribed)
        assertEquals(listOf(ActionMessage("Subscribed")), msgs)
    }

    @Test fun `a toggle whose state changed to the intended one before it ran sends nothing`() = subTest {
        lists.listState.value = mapOf(seriesKey to ListActions(subscribeForm, subscribed = false))
        val vm = subVm(); advanceUntilIdle()
        vm.toggleSubscribe()
        lists.listState.value = mapOf(seriesKey to ListActions(subscribeForm, subscribed = true))
        advanceUntilIdle()
        assertTrue(lists.calls.isEmpty())
        assertEquals(listOf(ActionMessage("Subscribed")), msgs)
    }

    @Test fun `a failed toggle rolls back and says so`() = subTest {
        lists.listState.value = mapOf(seriesKey to ListActions(subscribeForm, subscribed = true))
        lists.fail = Loaded.Error(FetchResult.RateLimited(30))
        val vm = subVm(); advanceUntilIdle()
        vm.toggleSubscribe(); advanceUntilIdle()
        assertTrue(vm.subscribe.value.subscribed)
        assertEquals(listOf(ActionMessage("AO3 asked us to slow down. Try again in 30 s.")), msgs)
    }

    private class FakeLists : ListActionSource {
        override val actingAccount = MutableStateFlow<String?>("owner")
        override val listState = MutableStateFlow<Map<String, ListActions>>(emptyMap())
        val calls = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        var fail: Loaded.Error? = null
        var refreshed: ListActions? = null
        override suspend fun refreshList(listUrl: HttpUrl): Loaded<ListActions> {
            calls += "refresh:$listUrl"
            val r = refreshed ?: return Loaded.Error(FetchResult.Failed(null))
            listState.value = listState.value + (listKey(listUrl)!! to r)
            return Loaded.Ok(r)
        }
        override suspend fun toggleListSubscribe(listUrl: HttpUrl): Loaded<Boolean> {
            calls += "toggle:$listUrl"
            val key = listKey(listUrl)!!
            val start = listState.value.getValue(key)
            listState.value = listState.value + (key to start.copy(subscribed = !start.subscribed))
            gate?.await()
            fail?.let { listState.value = listState.value + (key to start); return it }
            return Loaded.Ok(!start.subscribed)
        }
    }

    @Test fun `anything but an AO3 list url is rejected without a request`() = test {
        for (bad in listOf(null, "", "https://evil.example/series/9", "http://archiveofourown.org/series/9", "https://archiveofourown.org/works/1")) {
            val vm = vm(bad)
            advanceUntilIdle()
            assertTrue(vm.state.value.invalid)
            assertFalse(vm.state.value.loading)
        }
        assertTrue(source.calls.isEmpty())
    }

    @Test fun `page 1 loads with summaries, links and part labels`() = test {
        source.respond(1, page(1, true, 1, 2))
        val vm = vm()
        assertTrue(vm.state.value.loading)
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(listOf(series to 1), source.calls)
        assertEquals(listOf(1L, 2L), s.works.map { it.workId })
        assertEquals("S1", (s.summaries.getValue(1L).single() as Block.Text).text)
        val links = s.links.getValue(1L)
        // This series' own link is a plain "Part N" label; another series stays tappable.
        assertEquals(listOf("Part 1 of Saga", "Part 3 of Other"), links.series.map { it.label })
        assertNull(links.series[0].url)
        assertEquals("https://archiveofourown.org/series/77".toHttpUrl(), links.series[1].url)
        assertEquals("https://archiveofourown.org/users/bob/works".toHttpUrl(), links.authors.single().url)
        assertTrue(s.hasNext)
        assertFalse(s.loading)
        assertTrue(s.loaded)
    }

    @Test fun `paging is one at a time, appended and deduped`() = test {
        source.respond(1, page(1, true, 1, 2))
        val p2 = CompletableDeferred<Loaded<WorkListPage>>()
        source.pending[2] = p2
        val vm = vm()
        advanceUntilIdle()
        vm.loadNextPage()
        vm.loadNextPage()
        advanceUntilIdle()
        assertEquals(listOf(series to 1, series to 2), source.calls)
        p2.complete(Loaded.Ok(page(2, false, 2, 3)))
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L, 3L), vm.state.value.works.map { it.workId })
        vm.loadNextPage()
        advanceUntilIdle()
        assertEquals(2, source.calls.size)
    }

    @Test fun `a failed next page keeps the works and retries that page`() = test {
        source.respond(1, page(1, true, 1))
        source.results[2] = Loaded.Error(FetchResult.Blocked)
        val vm = vm()
        advanceUntilIdle()
        vm.loadNextPage()
        advanceUntilIdle()
        var s = vm.state.value
        assertEquals(FetchResult.Blocked, s.error)
        assertEquals(listOf(1L), s.works.map { it.workId })
        vm.loadNextPage()   // no paging past an error
        advanceUntilIdle()
        assertEquals(2, source.calls.size)
        source.respond(2, page(2, false, 4))
        vm.retry()
        advanceUntilIdle()
        s = vm.state.value
        assertNull(s.error)
        assertEquals(listOf(1L, 4L), s.works.map { it.workId })
        assertEquals(listOf(series to 1, series to 2, series to 2), source.calls)
    }

    @Test fun `a failed first page retries page 1`() = test {
        source.results[1] = Loaded.Error(FetchResult.NotFound)
        val vm = vm()
        advanceUntilIdle()
        assertEquals(FetchResult.NotFound, vm.state.value.error)
        assertFalse(vm.state.value.loaded)
        source.respond(1, page(1, false))
        vm.retry()
        advanceUntilIdle()
        assertTrue(vm.state.value.loaded)
        assertTrue(vm.state.value.works.isEmpty())
        assertNull(vm.state.value.error)
    }

    @Test fun `blank title falls back to the series heading`() = test {
        source.respond(1, page(1, false, 1))
        val vm = vm(title = "")
        advanceUntilIdle()
        assertEquals("Saga heading", vm.state.value.title)
    }

    @Test fun `badges follow progress and read marks`() = test {
        source.respond(1, page(1, false, 1, 2))
        val vm = vm()
        advanceUntilIdle()
        progress.upsert(ProgressEntity(workId = 1, workTitle = "T1", chapterId = 11, chapterNumber = 3, chaptersTotal = null, blockIndex = 0, blockOffsetPx = 0, updatedAt = 1))
        marks.upsertAll(listOf(ReadMarkEntity(2, 1)))
        advanceUntilIdle()
        assertEquals(3, vm.state.value.progressByWork.getValue(1L).chapterNumber)
        assertEquals(setOf(2L), vm.state.value.readMarks)
    }

    private fun page(n: Int, hasNext: Boolean, vararg ids: Long) =
        WorkListPage(WorkPage(ids.map { blurb(it) }, n, hasNext), "Saga heading")

    private fun blurb(id: Long) = WorkBlurb(
        workId = id, title = "T$id", authors = listOf("bob"), fandoms = emptyList(), rating = null,
        warnings = emptyList(), categories = emptyList(), complete = null, relationships = emptyList(),
        characters = emptyList(), freeforms = emptyList(), summaryHtml = "<p>S$id</p>", language = null, words = null,
        chaptersPosted = null, chaptersTotal = null, kudos = null, hits = null, bookmarks = null,
        comments = null, updated = null,
        authorLinks = listOf(AuthorLink("bob", "bob", "bob")),
        series = listOf(SeriesLink(9, "Saga", id.toInt()), SeriesLink(77, "Other", 3)),
    )

    private class FakeSource : WorkListSource {
        val calls = mutableListOf<Pair<String, Int>>()
        val results = mutableMapOf<Int, Loaded<WorkListPage>>()
        val pending = mutableMapOf<Int, CompletableDeferred<Loaded<WorkListPage>>>()

        fun respond(n: Int, p: WorkListPage) {
            pending.remove(n)
            results[n] = Loaded.Ok(p)
        }

        override suspend fun load(url: HttpUrl, page: Int): Loaded<WorkListPage> {
            calls += url.toString() to page
            pending[page]?.let { return it.await() }
            return results[page] ?: Loaded.Error(FetchResult.NotFound)
        }
    }

    private class FakeProgressDao : ProgressDao {
        val rows = MutableStateFlow<List<ProgressEntity>>(emptyList())
        override fun observeLatest(): Flow<ProgressEntity?> = rows.map { l -> l.maxByOrNull { it.updatedAt } }
        override suspend fun get(workId: Long) = rows.value.firstOrNull { it.workId == workId }
        override suspend fun upsert(p: ProgressEntity) { rows.value = rows.value.filterNot { it.workId == p.workId } + p }
        override fun observeFor(workIds: List<Long>): Flow<List<ProgressEntity>> =
            rows.map { l -> l.filter { it.workId in workIds } }
    }

    private class FakeReadMarkDao : ReadMarkDao {
        val rows = MutableStateFlow<List<ReadMarkEntity>>(emptyList())
        override fun observeFor(workIds: List<Long>): Flow<List<ReadMarkEntity>> =
            rows.map { l -> l.filter { it.workId in workIds } }
        override suspend fun upsertAll(marks: List<ReadMarkEntity>) {
            val ids = marks.map { it.workId }.toSet()
            rows.value = rows.value.filterNot { it.workId in ids } + marks
        }
    }
}
