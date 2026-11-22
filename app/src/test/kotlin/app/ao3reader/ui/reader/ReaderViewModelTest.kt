package app.ao3reader.ui.reader

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.SavedStateHandle
import app.ao3reader.ao3.model.Block
import app.ao3reader.ao3.model.ChapterContent
import app.ao3reader.ao3.model.ChapterRef
import app.ao3reader.ao3.net.FetchResult
import app.ao3reader.ao3.parse.Ao3Form
import app.ao3reader.ao3.parse.BookmarkInput
import app.ao3reader.ao3.parse.WorkActions
import app.ao3reader.data.ActionRunner
import app.ao3reader.data.ActionMayHaveTakenEffectException
import app.ao3reader.data.ActionUnconfirmedException
import app.ao3reader.data.ChapterSource
import app.ao3reader.data.NotSignedInException
import app.ao3reader.data.WorkActionSource
import app.ao3reader.ui.common.ActionMessage
import app.ao3reader.ui.common.WorkAction
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertSame
import app.ao3reader.data.Loaded
import app.ao3reader.data.ReaderFont
import app.ao3reader.data.SettingsStore
import app.ao3reader.data.db.DownloadEntity
import app.ao3reader.data.db.DownloadJob
import app.ao3reader.data.db.ProgressDao
import app.ao3reader.data.db.SourceKind
import app.ao3reader.data.db.ProgressEntity
import app.ao3reader.ui.Routes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var source: FakeChapters
    private lateinit var progress: FakeProgressDao
    private lateinit var prefs: MemoryPrefs

    private val refs = listOf(ChapterRef(101, 1, "One"), ChapterRef(102, 2, "Two"), ChapterRef(103, 3, "Three"))

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        source = FakeChapters(refs)
        progress = FakeProgressDao()
        prefs = MemoryPrefs()
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(routeChapterId: Long? = null, routeNumber: Int = 1, handle: SavedStateHandle = SavedStateHandle()) =
        ReaderViewModel(WORK, routeChapterId, routeNumber, source, progress, SettingsStore(prefs), handle, clock = { 1000L })

    /** What navigation puts in the entry's SavedStateHandle: the route arguments. */
    private fun navHandle(chapterId: Long?, number: Int) =
        SavedStateHandle(mapOf(Routes.ARG_CHAPTER_ID to chapterId?.toString(), Routes.ARG_NUMBER to number))

    private fun test(body: suspend TestScope.() -> Unit) = runTest(dispatcher) { body() }

    private fun saved(chapterId: Long?, number: Int, block: Int, offset: Int) =
        ProgressEntity(WORK, "Work", chapterId, number, 3, block, offset, 1L)

    @Test fun `opens at saved progress chapter with restore position`() = test {
        progress.rows.value = listOf(saved(102, 2, 7, 33))
        val vm = vm(); advanceUntilIdle()
        val s = vm.state.value
        assertEquals(1, s.index)
        assertEquals(2, s.content?.ref?.number)
        assertEquals(7 to 33, s.restore)
        assertFalse(s.loading)
        assertEquals(refs, s.chapters)
    }

    @Test fun `opens at route chapter over saved progress`() = test {
        progress.rows.value = listOf(saved(102, 2, 7, 33))
        val vm = vm(routeChapterId = 103, routeNumber = 3); advanceUntilIdle()
        val s = vm.state.value
        assertEquals(2, s.index)
        assertEquals(3, s.content?.ref?.number)
        assertNull(s.restore)
    }

    @Test fun `route chapter in nav handle still beats saved progress on a fresh open`() = test {
        progress.rows.value = listOf(saved(102, 2, 7, 33))
        val vm = vm(routeChapterId = 103, routeNumber = 3, handle = navHandle(103, 3)); advanceUntilIdle()
        assertEquals(2, vm.state.value.index)
    }

    @Test fun `recreated from the same SavedStateHandle opens at the chapter navigated to`() = test {
        // Opened from Continue at chapter 1, swiped on to chapter 3, then the process died.
        val handle = navHandle(101, 1)
        val first = vm(routeChapterId = 101, routeNumber = 1, handle = handle); advanceUntilIdle()
        first.goTo(2); advanceUntilIdle()
        assertEquals("103", handle.get<String>(Routes.ARG_CHAPTER_ID))
        assertEquals(3, handle.get<Int>(Routes.ARG_NUMBER))
        // Navigation restores the entry with its original route; the handle keeps what the VM wrote.
        val restored = vm(routeChapterId = 101, routeNumber = 1, handle = handle); advanceUntilIdle()
        assertEquals(2, restored.state.value.index)
        assertEquals(3, restored.state.value.content?.ref?.number)
    }

    @Test fun `fallback load records the loaded chapter in the handle`() = test {
        progress.rows.value = listOf(saved(102, 2, 7, 33))
        source.listResult = Loaded.Error(FetchResult.Failed(null))
        val handle = navHandle(null, 1)
        vm(routeChapterId = null, routeNumber = 1, handle = handle); advanceUntilIdle()
        assertEquals("102", handle.get<String>(Routes.ARG_CHAPTER_ID))
        assertEquals(2, handle.get<Int>(Routes.ARG_NUMBER))
    }

    @Test fun `retry on the fallback chapter list fetches the chapter list again`() = test {
        source.listResult = Loaded.Error(FetchResult.Failed(null))
        val vm = vm(routeChapterId = 102, routeNumber = 2); advanceUntilIdle()
        assertFalse(vm.state.value.chapterListLoaded)
        source.listResult = Loaded.Ok(refs)
        vm.retry(); advanceUntilIdle()
        val s = vm.state.value
        assertTrue(s.chapterListLoaded)
        assertEquals(refs, s.chapters)
        assertEquals(1, s.index)
        assertEquals(2, s.content?.ref?.number)
    }

    @Test fun `opens at first chapter with no route or progress`() = test {
        val vm = vm(); advanceUntilIdle()
        assertEquals(0, vm.state.value.index)
        assertNull(vm.state.value.restore)
    }

    // ---- downloaded works (plan 3, Task 6) ----

    private val online13 = (1..13).map { ChapterRef(100L + it, it, "C$it") }

    private fun downloadRow(stored: Int) = DownloadEntity(
        WORK, "Work", "A", null, stored, stored, SourceKind.FULL_WORK_PAGE, 1, 1L, 10L, true, DownloadJob.NONE, null, 1L,
    )

    /** A work downloaded with chapters 1..[stored]; AO3 has 13. */
    private fun downloadedVm(stored: Int, routeChapterId: Long? = null, routeNumber: Int = 1): ReaderViewModel {
        source = FakeChapters(online13).apply { listResult = Loaded.Ok(online13.take(stored)) }
        return ReaderViewModel(
            WORK, routeChapterId, routeNumber, source, progress, SettingsStore(prefs), SavedStateHandle(), clock = { 1000L },
            downloadState = MutableStateFlow(downloadRow(stored)),
        )
    }

    @Test fun resumeBeyondStored_opensOnlineChapter() = test {
        progress.rows.value = listOf(ProgressEntity(WORK, "Work", 113, 13, 13, 4, 5, 1L))
        val vm = downloadedVm(12)
        advanceTimeBy(1_000)
        // Waiting for the merged list: not opened at chapter 1 meanwhile.
        assertTrue(source.chapterCalls.isEmpty())
        assertTrue(vm.state.value.loading)
        source.updates.value = online13
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(12, s.index)
        assertEquals(13, s.content?.ref?.number)
        assertEquals(listOf(online13[12]), source.chapterCalls)
        assertEquals(online13, s.chapters)
        assertEquals(4 to 5, s.restore)
        assertEquals(downloadRow(12), s.download)
    }

    @Test fun resumeBeyondStored_routeChapterId_opensOnlineChapter() = test {
        val vm = downloadedVm(12, routeChapterId = 113, routeNumber = 13)
        runCurrent()
        source.updates.value = online13
        advanceUntilIdle()
        assertEquals(12, vm.state.value.index)
        assertEquals(listOf(online13[12]), source.chapterCalls)
    }

    @Test fun resumeBeyondStored_offline_fallsBackToLastStored() = test {
        progress.rows.value = listOf(ProgressEntity(WORK, "Work", 113, 13, 13, 4, 5, 1L))
        val vm = downloadedVm(12)
        advanceTimeBy(1_000)
        assertTrue(source.chapterCalls.isEmpty())
        // The online check failed: the source answers with the stored list, and the reader opens at once.
        source.updates.value = online13.take(12)
        runCurrent()
        assertTrue(currentTime < ReaderViewModel.LIST_UPDATE_WAIT_MS)
        val s = vm.state.value
        assertEquals(11, s.index)
        assertEquals(12, s.content?.ref?.number)
        assertEquals(online13.take(12), s.chapters)
        assertTrue(s.chapterListLoaded)
    }

    @Test fun listUpdate_keepsCurrentChapter() = test {
        val vm = downloadedVm(3, routeChapterId = 102, routeNumber = 2)
        advanceUntilIdle()
        assertEquals(1, vm.state.value.index)
        val calls = source.chapterCalls.size
        // Even a list that shifts positions keeps the chapter being read selected.
        val shifted = listOf(ChapterRef(100, 0, "Prologue")) + online13
        source.updates.value = shifted
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(shifted, s.chapters)
        assertEquals(2, s.index)
        assertEquals(2, s.content?.ref?.number)
        assertEquals(calls, source.chapterCalls.size)
    }

    @Test fun listUpdate_appendsNext_prefetchesIt() = test {
        // Reading chapter 3, the last one stored: nothing after it to prefetch yet.
        val vm = downloadedVm(3, routeChapterId = 103, routeNumber = 3)
        advanceUntilIdle()
        assertEquals(2, vm.state.value.index)
        assertTrue(source.prefetched.isEmpty())
        source.updates.value = online13
        advanceUntilIdle()
        assertEquals(listOf(online13[3]), source.prefetched)
    }

    @Test fun listUpdate_nextAlreadyListed_noPrefetch() = test {
        val vm = downloadedVm(3, routeChapterId = 102, routeNumber = 2)
        advanceUntilIdle()
        assertEquals(listOf(online13[2]), source.prefetched)
        source.updates.value = online13
        advanceUntilIdle()
        assertEquals(1, vm.state.value.index)
        assertEquals(listOf(online13[2]), source.prefetched)
    }

    @Test fun routeChapterIdStored_noWait() = test {
        val vm = downloadedVm(12, routeChapterId = 105, routeNumber = 5)
        advanceTimeBy(10)
        val s = vm.state.value
        assertEquals(4, s.index)
        assertEquals(5, s.content?.ref?.number)
        assertFalse(s.loading)
    }

    @Test fun resumeBeyondStored_timeout_fallsBackToLastStored() = test {
        // No answer at all from the source: the timeout still opens the last stored chapter.
        progress.rows.value = listOf(ProgressEntity(WORK, "Work", 113, 13, 13, 4, 5, 1L))
        val vm = downloadedVm(12)
        advanceTimeBy(ReaderViewModel.LIST_UPDATE_WAIT_MS - 1)
        assertTrue(source.chapterCalls.isEmpty())
        advanceUntilIdle()
        assertEquals(11, vm.state.value.index)
    }

    @Test fun routeChapterIdOfNullIdStored_noWait() = test {
        // A one-shot stored without a chapter id, opened from History with AO3's id for it.
        val oneShot = ChapterRef(null, 1, "Only")
        source = FakeChapters(listOf(oneShot))
        val vm = ReaderViewModel(
            WORK, 555, 1, source, progress, SettingsStore(prefs), SavedStateHandle(), clock = { 1000L },
            downloadState = MutableStateFlow(downloadRow(1)),
        )
        advanceTimeBy(10)
        val s = vm.state.value
        assertEquals(0, s.index)
        assertFalse(s.loading)
        assertEquals(1, s.content?.ref?.number)
    }

    @Test fun progressStored_noWait() = test {
        progress.rows.value = listOf(ProgressEntity(WORK, "Work", 104, 4, 12, 0, 0, 1L))
        val vm = downloadedVm(12)
        advanceTimeBy(10)
        assertEquals(3, vm.state.value.index)
        assertFalse(vm.state.value.loading)
    }

    @Test fun noDownload_unresolvedProgress_noWait() = test {
        // Online-only work: today's behaviour, no waiting for list updates.
        progress.rows.value = listOf(ProgressEntity(WORK, "Work", 999, 9, 3, 0, 0, 1L))
        val vm = vm()
        advanceTimeBy(10)
        assertEquals(0, vm.state.value.index)
        assertFalse(vm.state.value.loading)
    }

    @Test fun `next loads following chapter, clears restore, prefetches index+1`() = test {
        progress.rows.value = listOf(saved(101, 1, 4, 5))
        val vm = vm(); advanceUntilIdle()
        assertEquals(4 to 5, vm.state.value.restore)
        vm.next(); advanceUntilIdle()
        val s = vm.state.value
        assertEquals(1, s.index)
        assertEquals(2, s.content?.ref?.number)
        assertNull(s.restore)
        assertEquals(refs[2], source.prefetched.last())
    }

    @Test fun `goTo saved progress chapter restores position`() = test {
        progress.rows.value = listOf(saved(103, 3, 9, 1))
        val vm = vm(routeChapterId = 101); advanceUntilIdle()
        assertNull(vm.state.value.restore)
        vm.goTo(2); advanceUntilIdle()
        assertEquals(9 to 1, vm.state.value.restore)
    }

    @Test fun `next at last chapter does nothing`() = test {
        val vm = vm(routeChapterId = 103); advanceUntilIdle()
        val calls = source.chapterCalls.size
        vm.next(); advanceUntilIdle()
        assertEquals(2, vm.state.value.index)
        assertEquals(calls, source.chapterCalls.size)
    }

    @Test fun `previous at first does nothing`() = test {
        val vm = vm(); advanceUntilIdle()
        val calls = source.chapterCalls.size
        vm.previous(); advanceUntilIdle()
        assertEquals(0, vm.state.value.index)
        assertEquals(calls, source.chapterCalls.size)
    }

    @Test fun `previous goes back one chapter`() = test {
        val vm = vm(routeChapterId = 102); advanceUntilIdle()
        vm.previous(); advanceUntilIdle()
        assertEquals(0, vm.state.value.index)
        assertEquals(1, vm.state.value.content?.ref?.number)
    }

    @Test fun `only current chapter content held`() = test {
        val vm = vm(); advanceUntilIdle()
        vm.next(); advanceUntilIdle()
        assertEquals(2, vm.state.value.content?.ref?.number)
        assertEquals(listOf(1, 2), source.chapterCalls.map { it.number })
    }

    @Test fun `scroll saves progress after debounce`() = test {
        val vm = vm(); advanceUntilIdle()
        vm.onScroll(5, 12)
        advanceTimeBy(499); runCurrent()
        assertEquals(0, progress.upserts.size)
        vm.onScroll(6, 20)
        advanceTimeBy(499); runCurrent()
        assertEquals(0, progress.upserts.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, progress.upserts.size)
        val p = progress.upserts.single()
        assertEquals(6, p.blockIndex)
        assertEquals(20, p.blockOffsetPx)
        assertEquals(101L, p.chapterId)
        assertEquals(1, p.chapterNumber)
        assertEquals(3, p.chaptersTotal)
        assertEquals("Work 7", p.workTitle)
        assertEquals(1000L, p.updatedAt)
        advanceUntilIdle()
        assertEquals(1, progress.upserts.size)
    }

    @Test fun `scroll ignored until restore applied`() = test {
        progress.rows.value = listOf(saved(102, 2, 7, 33))
        val vm = vm(); advanceUntilIdle()
        vm.onScroll(0, 0); advanceUntilIdle()
        assertEquals(0, progress.upserts.size)
        vm.restoreApplied()
        assertNull(vm.state.value.restore)
        vm.onScroll(7, 33); advanceUntilIdle()
        assertEquals(1, progress.upserts.size)
    }

    @Test fun `flushProgress saves immediately`() = test {
        val vm = vm(); advanceUntilIdle()
        vm.onScroll(3, 10)
        vm.flushProgress(); runCurrent()
        assertEquals(1, progress.upserts.size)
        assertEquals(3, progress.upserts.single().blockIndex)
        advanceUntilIdle()
        assertEquals(1, progress.upserts.size)
    }

    @Test fun `flush during an in-flight debounced write does not lose the position`() = test {
        val vm = vm(); advanceUntilIdle()
        val gate = CompletableDeferred<Unit>().also { progress.upsertGate = it }
        vm.onScroll(5, 12)
        advanceTimeBy(ReaderViewModel.SAVE_DEBOUNCE_MS); runCurrent()   // the write has started and is waiting
        vm.flushProgress()   // cancels the debounce job mid-upsert
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(5, progress.rows.value.single().blockIndex)
    }

    @Test fun `scroll on fallback chapter list keeps the known chapter total`() = test {
        progress.rows.value = listOf(saved(102, 2, 7, 33))
        source.listResult = Loaded.Error(FetchResult.Failed(null))
        val vm = vm(routeChapterId = 102, routeNumber = 2); advanceUntilIdle()
        assertFalse(vm.state.value.chapterListLoaded)
        vm.restoreApplied()
        vm.onScroll(8, 0); advanceUntilIdle()
        assertEquals(3, progress.upserts.last().chaptersTotal)
    }

    @Test fun `flushProgress with nothing pending writes nothing`() = test {
        val vm = vm(); advanceUntilIdle()
        vm.flushProgress(); advanceUntilIdle()
        assertEquals(0, progress.upserts.size)
    }

    @Test fun `error then retry loads`() = test {
        source.failChapter[1] = FetchResult.Blocked
        val vm = vm(); advanceUntilIdle()
        assertEquals(FetchResult.Blocked, vm.state.value.error)
        assertNull(vm.state.value.content)
        assertFalse(vm.state.value.loading)
        source.failChapter.clear()
        vm.retry(); advanceUntilIdle()
        assertNull(vm.state.value.error)
        assertEquals(1, vm.state.value.content?.ref?.number)
    }

    @Test fun `chapter list failure falls back to loaded chapter`() = test {
        source.listResult = Loaded.Error(FetchResult.Failed(null))
        val vm = vm(routeChapterId = 102, routeNumber = 2); advanceUntilIdle()
        val s = vm.state.value
        assertNull(s.error)
        assertEquals(2, s.content?.ref?.number)
        assertEquals(1, s.chapters.size)
        assertEquals(0, s.index)
    }

    @Test fun `load faster than spinner delay never shows spinner`() = test {
        val vm = vm(); advanceUntilIdle()
        assertFalse(vm.state.value.showSpinner)
        val gate = CompletableDeferred<Unit>().also { source.gate = it }
        vm.next()
        advanceTimeBy(ReaderViewModel.SPINNER_DELAY_MS - 1); runCurrent()
        assertTrue(vm.state.value.loading)
        assertFalse(vm.state.value.showSpinner)
        gate.complete(Unit); runCurrent()
        assertFalse(vm.state.value.loading)
        advanceUntilIdle()
        assertFalse(vm.state.value.showSpinner)
    }

    @Test fun `slow load shows spinner after delay and hides it when content arrives`() = test {
        val vm = vm(); advanceUntilIdle()
        val gate = CompletableDeferred<Unit>().also { source.gate = it }
        vm.next()
        advanceTimeBy(ReaderViewModel.SPINNER_DELAY_MS - 1); runCurrent()
        assertFalse(vm.state.value.showSpinner)
        advanceTimeBy(1); runCurrent()
        assertTrue(vm.state.value.showSpinner)
        gate.complete(Unit); advanceUntilIdle()
        assertFalse(vm.state.value.showSpinner)
        assertEquals(2, vm.state.value.content?.ref?.number)
    }

    @Test fun `spinner delay restarts on each chapter change`() = test {
        val vm = vm(); advanceUntilIdle()
        source.gate = CompletableDeferred()
        vm.next()
        advanceTimeBy(300); runCurrent()
        vm.next()   // index 2 while 1 still loading
        advanceTimeBy(300); runCurrent()
        assertFalse(vm.state.value.showSpinner)
        advanceTimeBy(50); runCurrent()
        assertTrue(vm.state.value.showSpinner)
    }

    @Test fun `toggleControls flips visibility`() = test {
        val vm = vm(); advanceUntilIdle()
        val before = vm.state.value.controlsVisible
        vm.toggleControls()
        assertEquals(!before, vm.state.value.controlsVisible)
    }

    @Test fun `font size change persists through SettingsStore`() = test {
        val vm = vm(); advanceUntilIdle()
        vm.setFontSize(22); advanceUntilIdle()
        assertEquals(22, SettingsStore(prefs).fontSizeSp.first())
        assertEquals(22, vm.state.value.fontSizeSp)
        vm.setFont(ReaderFont.SERIF); advanceUntilIdle()
        assertEquals(ReaderFont.SERIF, SettingsStore(prefs).fontFamily.first())
        assertEquals(ReaderFont.SERIF, vm.state.value.font)
    }

    // ---- account actions (Task 11) ----

    private lateinit var acts: FakeActions
    private lateinit var runner: ActionRunner
    private val msgs = mutableListOf<ActionMessage>()

    private fun TestScope.actionVm(handle: SavedStateHandle = SavedStateHandle()): ReaderViewModel {
        // Not backgroundScope: advanceUntilIdle stops once only background work is left.
        runner = ActionRunner(side)
        val vm = ReaderViewModel(WORK, null, 1, source, progress, SettingsStore(prefs), handle, clock = { 1000L },
            actionSource = acts, runner = runner)
        side.launch { vm.messages.collect { msgs += it } }
        return vm
    }

    /** The app scope stand-in (runner, collectors); cancelled when the test ends. */
    private lateinit var side: CoroutineScope

    private fun actionsTest(body: suspend TestScope.() -> Unit) = test {
        acts = FakeActions()
        side = CoroutineScope(dispatcher + SupervisorJob())
        try { body() } finally { side.cancel() }
    }

    // ---- download button (plan 3, Task 7) ----

    private val requests = mutableListOf<Triple<Long, String, List<String>>>()

    private fun TestScope.downloadVm(row: DownloadEntity?): ReaderViewModel {
        runner = ActionRunner(side)
        return ReaderViewModel(WORK, null, 1, source, progress, SettingsStore(prefs), SavedStateHandle(), clock = { 1000L },
            actionSource = acts, runner = runner, downloadState = MutableStateFlow(row),
            requestDownload = { id, t, a -> requests += Triple(id, t, a) })
    }

    @Test fun onDownload_requests() = actionsTest {
        val vm = downloadVm(null); advanceUntilIdle()
        vm.onDownload(); advanceUntilIdle()
        assertEquals(1, requests.size)
        assertEquals(WORK, requests[0].first)
    }

    @Test fun onDownload_whenDownloaded_noop() = actionsTest {
        val vm = downloadVm(downloadRow(3)); advanceUntilIdle()
        vm.onDownload(); advanceUntilIdle()
        assertTrue(requests.isEmpty())
    }

    @Test fun onDownload_signedOut_stillRequests() = actionsTest {
        acts.actingAccount.value = null
        val vm = downloadVm(null); advanceUntilIdle()
        vm.onDownload(); advanceUntilIdle()
        assertEquals(1, requests.size)
    }

    @Test fun onDownload_requestThrows_showsMessage() = actionsTest {
        runner = ActionRunner(side)
        val vm = ReaderViewModel(WORK, null, 1, source, progress, SettingsStore(prefs), SavedStateHandle(), clock = { 1000L },
            actionSource = acts, runner = runner, downloadState = MutableStateFlow(null),
            requestDownload = { _, _, _ -> throw IllegalStateException("database is gone") })
        side.launch { vm.messages.collect { msgs += it } }
        advanceUntilIdle()
        vm.onDownload(); advanceUntilIdle()
        assertEquals(listOf(ActionMessage("Couldn't start the download.")), msgs)
        assertTrue(runner.running.value.isEmpty())
    }

    @Test fun `signed out shows the sign-in state and a tap sends nothing`() = actionsTest {
        acts.actingAccount.value = null
        val vm = actionVm(); advanceUntilIdle()
        assertFalse(vm.actions.value.signedIn)
        vm.kudos(); vm.toggleSubscribe(); advanceUntilIdle()
        assertTrue(acts.calls.isEmpty())
    }

    @Test fun `the row shows the current work's state and ignores other works`() = actionsTest {
        acts.state.value = mapOf(WORK to wa(kudosGiven = true, subscribed = true), 8L to wa())
        val vm = actionVm(); advanceUntilIdle()
        val ui = vm.actions.value
        assertTrue(ui.signedIn && ui.kudosGiven && ui.subscribed)
        assertFalse(ui.bookmarked || ui.markedForLater)
        val before = vm.actions.value
        acts.state.value = acts.state.value + (8L to wa(bookmarked = true)); advanceUntilIdle()
        assertSame(before, vm.actions.value)   // another work's change: no new value
        // Sign-out flips the row at once.
        acts.actingAccount.value = null; advanceUntilIdle()
        assertFalse(vm.actions.value.signedIn)
    }

    @Test fun `a work AO3 offers no form for shows that action unavailable`() = actionsTest {
        acts.state.value = mapOf(WORK to wa().copy(markForLater = null, comment = null))
        val vm = actionVm(); advanceUntilIdle()
        assertEquals(setOf(WorkAction.KUDOS, WorkAction.BOOKMARK, WorkAction.SUBSCRIBE), vm.actions.value.available)
    }

    @Test fun `kudos calls the repository, shows progress, then confirms`() = actionsTest {
        acts.state.value = mapOf(WORK to wa())
        val gate = CompletableDeferred<Unit>().also { acts.gate = it }
        val vm = actionVm(); advanceUntilIdle()
        vm.kudos(); runCurrent()
        assertEquals(setOf(WorkAction.KUDOS), vm.actions.value.running)
        vm.kudos(); runCurrent()                       // a second tap while it runs sends nothing
        assertEquals(listOf("kudos:7"), acts.calls)
        gate.complete(Unit); advanceUntilIdle()
        assertTrue(vm.actions.value.running.isEmpty())
        assertTrue(vm.actions.value.kudosGiven)
        assertEquals(listOf(ActionMessage("Kudos sent")), msgs)
    }

    @Test fun `kudos already given sends nothing`() = actionsTest {
        acts.state.value = mapOf(WORK to wa(kudosGiven = true))
        val vm = actionVm(); advanceUntilIdle()
        vm.kudos(); advanceUntilIdle()
        assertTrue(acts.calls.isEmpty())
    }

    @Test fun `each toggle calls its repository method`() = actionsTest {
        acts.state.value = mapOf(WORK to wa())
        val vm = actionVm(); advanceUntilIdle()
        vm.toggleLater(); advanceUntilIdle()
        vm.toggleSubscribe(); advanceUntilIdle()
        assertEquals(listOf("mfl:7", "sub:7"), acts.calls)
        assertEquals(listOf(ActionMessage("Saved for later"), ActionMessage("Subscribed")), msgs)
        assertTrue(vm.actions.value.markedForLater && vm.actions.value.subscribed)
        vm.toggleSubscribe(); advanceUntilIdle()
        assertEquals(ActionMessage("Unsubscribed"), msgs.last())
    }

    @Test fun `a toggle flips at once and rolls back when AO3 doesn't confirm`() = actionsTest {
        acts.state.value = mapOf(WORK to wa())
        acts.fail = Loaded.Error(FetchResult.Failed(ActionUnconfirmedException("no")))
        val gate = CompletableDeferred<Unit>().also { acts.gate = it }
        val vm = actionVm(); advanceUntilIdle()
        vm.toggleSubscribe(); runCurrent()
        assertTrue(vm.actions.value.subscribed)        // optimistic
        assertEquals(setOf(WorkAction.SUBSCRIBE), vm.actions.value.running)
        gate.complete(Unit); advanceUntilIdle()
        assertFalse(vm.actions.value.subscribed)       // rolled back
        assertEquals(listOf(ActionMessage("Couldn't confirm with AO3 — check the work page.")), msgs)
    }

    @Test fun `a toggle on a work whose state is not known yet reads AO3 first and never undoes what the owner meant`() = actionsTest {
        // Shown as not subscribed (unknown); AO3 says already subscribed: tapping Subscribe must not unsubscribe.
        acts.refreshed = wa(subscribed = true)
        val vm = actionVm(); advanceUntilIdle()
        assertFalse(vm.actions.value.subscribed)
        vm.toggleSubscribe(); advanceUntilIdle()
        assertEquals(listOf("refresh:7"), acts.calls)
        assertEquals(listOf(ActionMessage("Subscribed")), msgs)
        assertTrue(vm.actions.value.subscribed)
    }

    @Test fun `a toggle on an unknown work that is not yet in that state toggles after reading`() = actionsTest {
        acts.refreshed = wa()
        val vm = actionVm(); advanceUntilIdle()
        vm.toggleLater(); advanceUntilIdle()
        assertEquals(listOf("refresh:7", "mfl:7"), acts.calls)
    }

    @Test fun `not signed in any more offers the account screen`() = actionsTest {
        acts.state.value = mapOf(WORK to wa())
        acts.fail = Loaded.Error(FetchResult.Failed(NotSignedInException()))
        val vm = actionVm(); advanceUntilIdle()
        vm.kudos(); advanceUntilIdle()
        assertEquals(listOf(ActionMessage("Sign in again to do that.", toAccount = true)), msgs)
    }

    @Test fun `an action keeps running after the reader is closed`() = actionsTest {
        acts.state.value = mapOf(WORK to wa())
        val gate = CompletableDeferred<Unit>().also { acts.gate = it }
        val vm = actionVm(); advanceUntilIdle()
        vm.toggleSubscribe(); runCurrent()
        vm.viewModelScope.cancel()                     // the reader left
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(listOf("sub:7"), acts.calls)
        assertEquals(listOf("sub:7"), acts.finished)
        assertTrue(runner.running.value.isEmpty())
    }

    @Test fun `bookmark sheet is prefilled from the existing bookmark and closes only once saved`() = actionsTest {
        val existing = BookmarkInput("my notes", listOf("a", "b"), isPrivate = true, isRec = false)
        acts.state.value = mapOf(WORK to wa(bookmarked = true))
        acts.bookmark = existing
        val vm = actionVm(); advanceUntilIdle()
        vm.openBookmark(); advanceUntilIdle()
        val sheet = vm.sheet.value as ActionSheet.Bookmark
        assertEquals(existing, sheet.initial)
        assertFalse(sheet.loading)
        val edited = existing.copy(notes = "new", isRec = true)
        acts.fail = Loaded.Error(FetchResult.Failed(java.io.IOException()))
        vm.saveBookmark(edited); advanceUntilIdle()
        // Failed: still open (the sheet keeps the input), the error shown in the sheet, not under it.
        assertEquals("Couldn't reach AO3.", (vm.sheet.value as ActionSheet.Bookmark).error)
        assertTrue(msgs.isEmpty())
        acts.fail = null
        vm.saveBookmark(edited); advanceUntilIdle()
        assertEquals(listOf("bookmark:7:$edited", "bookmark:7:$edited"), acts.calls)
        assertNull(vm.sheet.value)
        assertEquals(ActionMessage("Bookmarked"), msgs.last())
    }

    @Test fun `bookmark sheet reads AO3 first when the work's state is not known`() = actionsTest {
        val existing = BookmarkInput("n", emptyList(), isPrivate = false, isRec = true)
        acts.refreshed = wa(bookmarked = true)
        acts.bookmarkAfterRefresh = existing
        val gate = CompletableDeferred<Unit>().also { acts.refreshGate = it }
        val vm = actionVm(); advanceUntilIdle()
        vm.openBookmark(); runCurrent()
        assertTrue((vm.sheet.value as ActionSheet.Bookmark).loading)
        gate.complete(Unit); advanceUntilIdle()
        val sheet = vm.sheet.value as ActionSheet.Bookmark
        assertFalse(sheet.loading)
        assertEquals(existing, sheet.initial)
        assertTrue(sheet.existing)
    }

    @Test fun `a new bookmark starts empty`() = actionsTest {
        acts.state.value = mapOf(WORK to wa())
        val vm = actionVm(); advanceUntilIdle()
        vm.openBookmark(); advanceUntilIdle()
        val sheet = vm.sheet.value as ActionSheet.Bookmark
        assertEquals(BookmarkInput("", emptyList(), isPrivate = false, isRec = false), sheet.initial)
        assertFalse(sheet.existing)
        assertTrue(acts.calls.isEmpty())
    }

    @Test fun `comment goes to the chapter shown, keeps the text on failure, clears it once posted`() = actionsTest {
        acts.state.value = mapOf(WORK to wa())
        val vm = actionVm(); advanceUntilIdle()
        vm.goTo(1); advanceUntilIdle()
        vm.openComment()
        assertEquals(ActionSheet.Comment(102L, 2), vm.sheet.value)
        vm.postComment(); advanceUntilIdle()           // blank: nothing sent
        assertTrue(acts.calls.isEmpty())
        vm.setCommentDraft("Lovely chapter")
        acts.fail = Loaded.Error(FetchResult.Failed(ActionUnconfirmedException("no")))
        vm.postComment(); advanceUntilIdle()
        assertEquals("Lovely chapter", vm.commentDraft.value)
        val failed = vm.sheet.value as ActionSheet.Comment
        assertEquals("Your comment may not have posted — check the work before trying again.", failed.error)
        assertTrue(failed.mustCheck && failed.failedOnce)
        assertTrue(msgs.isEmpty())                     // shown in the sheet, not in a snackbar under it
        vm.postComment(); advanceUntilIdle()           // may have posted: Post is off until checked
        assertEquals(1, acts.calls.size)
        acts.fail = null
        vm.postAgain(); advanceUntilIdle()
        assertEquals(listOf("comment:7:102:Lovely chapter", "comment:7:102:Lovely chapter"), acts.calls)
        assertEquals("", vm.commentDraft.value)
        assertNull(vm.sheet.value)
        assertEquals(ActionMessage("Comment posted"), msgs.last())
    }

    @Test fun `after a comment that may have gone through, editing the text enables Post again`() = actionsTest {
        acts.state.value = mapOf(WORK to wa())
        val vm = actionVm(); advanceUntilIdle()
        vm.openComment(); vm.setCommentDraft("Hi")
        acts.fail = Loaded.Error(FetchResult.Failed(ActionMayHaveTakenEffectException(FetchResult.Failed(null))))
        vm.postComment(); advanceUntilIdle()
        val s = vm.sheet.value as ActionSheet.Comment
        assertEquals("The connection dropped — this may have gone through. Check before retrying.", s.error)
        assertTrue(s.mustCheck)
        vm.setCommentDraft("Hi!")
        assertFalse((vm.sheet.value as ActionSheet.Comment).mustCheck)
        acts.fail = null
        vm.postComment(); advanceUntilIdle()
        assertEquals(listOf("comment:7:101:Hi", "comment:7:101:Hi!"), acts.calls)
        assertEquals(ActionMessage("Comment posted"), msgs.single())
    }

    @Test fun `a plain failure keeps Post enabled, and a failure after the sheet closed goes to a snackbar`() = actionsTest {
        acts.state.value = mapOf(WORK to wa())
        val vm = actionVm(); advanceUntilIdle()
        vm.openComment(); vm.setCommentDraft("Hi")
        acts.fail = Loaded.Error(FetchResult.Blocked)
        vm.postComment(); advanceUntilIdle()
        val s = vm.sheet.value as ActionSheet.Comment
        assertFalse(s.mustCheck)
        assertEquals("AO3 is blocking requests right now.", s.error)
        val gate = CompletableDeferred<Unit>().also { acts.gate = it }
        vm.postComment(); runCurrent()
        assertNull((vm.sheet.value as ActionSheet.Comment).error)   // cleared while posting
        vm.closeSheet()
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(listOf(ActionMessage("AO3 is blocking requests right now.")), msgs)
    }

    @Test fun `the guard after an uncertain post survives closing the sheet and process death`() = actionsTest {
        acts.state.value = mapOf(WORK to wa())
        val handle = SavedStateHandle()
        val vm = actionVm(handle); advanceUntilIdle()
        vm.openComment(); vm.setCommentDraft("Hi")
        acts.fail = Loaded.Error(FetchResult.Failed(ActionUnconfirmedException("no")))
        vm.postComment(); advanceUntilIdle()
        vm.closeSheet()
        vm.openComment()
        val reopened = vm.sheet.value as ActionSheet.Comment
        assertTrue(reopened.mustCheck && reopened.failedOnce)
        vm.postComment(); advanceUntilIdle()
        assertEquals(1, acts.calls.size)
        // Process death with the sheet closed, then reopened.
        vm.closeSheet()
        val restored = actionVm(handle); advanceUntilIdle()
        restored.openComment()
        assertTrue((restored.sheet.value as ActionSheet.Comment).mustCheck)
        // Restored with the sheet open.
        val again = actionVm(handle); advanceUntilIdle()
        assertTrue((again.sheet.value as ActionSheet.Comment).mustCheck)
        // Retyping the same text is not an edit.
        again.setCommentDraft("Hi")
        assertTrue((again.sheet.value as ActionSheet.Comment).mustCheck)
        // Editing clears the guard; the label stays "Post again".
        again.setCommentDraft("Hi!")
        val edited = again.sheet.value as ActionSheet.Comment
        assertFalse(edited.mustCheck)
        assertTrue(edited.failedOnce)
        acts.fail = null
        again.postComment(); advanceUntilIdle()
        assertEquals(2, acts.calls.size)
        // Posted: the guard and the label are gone for the next comment.
        again.openComment()
        assertEquals(ActionSheet.Comment(101L, 1), again.sheet.value)
    }

    @Test fun `a reopened or restored sheet under the guard says why Post is off`() = actionsTest {
        val line = "Your comment may not have posted — check the work before trying again."
        acts.state.value = mapOf(WORK to wa())
        val handle = SavedStateHandle()
        val vm = actionVm(handle); advanceUntilIdle()
        vm.openComment(); vm.setCommentDraft("Hi")
        acts.fail = Loaded.Error(FetchResult.Failed(ActionMayHaveTakenEffectException(FetchResult.Failed(null))))
        vm.postComment(); advanceUntilIdle()
        vm.closeSheet()
        vm.openComment()
        assertEquals(line, (vm.sheet.value as ActionSheet.Comment).error)
        // Restored with the sheet open (process death).
        val restored = actionVm(handle); advanceUntilIdle()
        assertEquals(line, (restored.sheet.value as ActionSheet.Comment).error)
        // Without the guard a reopened sheet shows no error.
        restored.setCommentDraft("Hi!")
        restored.closeSheet(); restored.openComment()
        assertNull((restored.sheet.value as ActionSheet.Comment).error)
    }

    @Test fun `post again does not clear the guard when the draft is blank`() = actionsTest {
        acts.state.value = mapOf(WORK to wa())
        val handle = SavedStateHandle()
        val vm = actionVm(handle); advanceUntilIdle()
        vm.openComment(); vm.setCommentDraft("Hi")
        acts.fail = Loaded.Error(FetchResult.Failed(ActionUnconfirmedException("no")))
        vm.postComment(); advanceUntilIdle()
        // A blank draft with the guard still set (restored that way).
        handle["action.commentDraft"] = ""
        val restored = actionVm(handle); advanceUntilIdle()
        acts.fail = null
        restored.postAgain(); advanceUntilIdle()
        assertTrue((restored.sheet.value as ActionSheet.Comment).mustCheck)
        assertEquals(1, acts.calls.size)
    }

    @Test fun `kudos AO3 already shows after reading an unknown work say so and send nothing`() = actionsTest {
        acts.refreshed = wa(kudosGiven = true)
        val vm = actionVm(); advanceUntilIdle()
        vm.kudos(); advanceUntilIdle()
        assertEquals(listOf("refresh:7"), acts.calls)
        assertEquals(listOf(ActionMessage("Kudos already given")), msgs)
    }

    @Test fun `a toggle whose state changed to the intended one before it ran sends nothing`() = actionsTest {
        acts.state.value = mapOf(WORK to wa())
        val vm = actionVm(); advanceUntilIdle()
        vm.toggleSubscribe()                           // meant: subscribe
        acts.state.value = mapOf(WORK to wa(subscribed = true))   // a page arrived meanwhile showing it
        advanceUntilIdle()
        assertTrue(acts.calls.isEmpty())
        assertEquals(listOf(ActionMessage("Subscribed")), msgs)
    }

    @Test fun `an open comment sheet and its draft survive process death`() = actionsTest {
        acts.state.value = mapOf(WORK to wa())
        val handle = SavedStateHandle()
        val first = actionVm(handle); advanceUntilIdle()
        first.goTo(1); advanceUntilIdle()
        first.openComment(); first.setCommentDraft("half written")
        val restored = actionVm(handle); advanceUntilIdle()
        assertEquals(ActionSheet.Comment(102L, 2), restored.sheet.value)
        assertEquals("half written", restored.commentDraft.value)
        restored.closeSheet()
        assertNull(actionVm(handle).sheet.value)
        assertEquals("half written", actionVm(handle).commentDraft.value)   // the draft outlives the sheet
    }

    private fun form(path: String) = Ao3Form("https://archiveofourown.org$path".toHttpUrl(), "post", emptyList())

    private fun wa(kudosGiven: Boolean = false, bookmarked: Boolean = false, later: Boolean = false, subscribed: Boolean = false) =
        WorkActions(
            kudos = form("/kudos"), kudosGiven = kudosGiven,
            bookmarkForm = form("/works/7/bookmarks"), bookmarked = bookmarked, bookmarkEditUrl = null,
            markForLater = form("/works/7/mark_for_later"), markedForLater = later,
            subscribe = form("/users/owner/subscriptions"), subscribed = subscribed,
            comment = form("/chapters/101/comments"),
        )

    /** Stands in for ActionRepository: records calls, applies the optimistic flip and rolls it back on error. */
    private class FakeActions : WorkActionSource {
        override val actingAccount = MutableStateFlow<String?>("owner")
        override val state = MutableStateFlow<Map<Long, WorkActions>>(emptyMap())
        val calls = mutableListOf<String>()
        val finished = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        var refreshGate: CompletableDeferred<Unit>? = null
        /** The next actions fail with this (refresh never does). */
        var fail: Loaded.Error? = null
        var refreshed: WorkActions? = null
        var bookmark: BookmarkInput? = null
        var bookmarkAfterRefresh: BookmarkInput? = null

        override fun existingBookmark(workId: Long) = bookmark
        override suspend fun refresh(workId: Long): Loaded<WorkActions> {
            calls += "refresh:$workId"
            refreshGate?.await()
            val r = refreshed ?: return Loaded.Error(FetchResult.Failed(null))
            state.value = state.value + (workId to r)
            bookmarkAfterRefresh?.let { bookmark = it }
            return Loaded.Ok(r)
        }
        override suspend fun kudos(workId: Long): Loaded<Unit> = act("kudos:$workId") {
            state.value = state.value + (workId to state.value[workId]!!.copy(kudosGiven = true))
        }
        override suspend fun toggleMarkForLater(workId: Long) =
            toggle("mfl:$workId", workId, { it.markedForLater }) { a, v -> a.copy(markedForLater = v) }
        override suspend fun toggleSubscribe(workId: Long) =
            toggle("sub:$workId", workId, { it.subscribed }) { a, v -> a.copy(subscribed = v) }
        override suspend fun saveBookmark(workId: Long, input: BookmarkInput) = act("bookmark:$workId:$input") {}
        override suspend fun comment(workId: Long, chapterId: Long?, text: String) = act("comment:$workId:$chapterId:$text") {}

        private suspend fun <T> act(call: String, onOk: () -> T): Loaded<T> {
            calls += call
            gate?.await()
            finished += call
            return fail ?: Loaded.Ok(onOk())
        }

        private suspend fun toggle(
            call: String, workId: Long, get: (WorkActions) -> Boolean, set: (WorkActions, Boolean) -> WorkActions,
        ): Loaded<Boolean> {
            val start = state.value[workId]!!
            val target = !get(start)
            state.value = state.value + (workId to set(start, target))   // the optimistic overlay
            val r = act(call) { target }
            if (r is Loaded.Error) state.value = state.value + (workId to start)
            return r
        }
    }

    private class FakeChapters(private val refs: List<ChapterRef>) : ChapterSource {
        var listResult: Loaded<List<ChapterRef>> = Loaded.Ok(refs)
        /** What [listUpdates] emits (null: nothing yet). */
        val updates = MutableStateFlow<List<ChapterRef>?>(null)
        override fun listUpdates(workId: Long): Flow<List<ChapterRef>> = updates.filterNotNull()
        val failChapter = mutableMapOf<Int, FetchResult>()
        val chapterCalls = mutableListOf<ChapterRef>()
        val prefetched = mutableListOf<ChapterRef>()
        /** When set, chapter() suspends until it completes (a slow network load). */
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun chapters(workId: Long) = listResult

        override suspend fun chapter(workId: Long, ref: ChapterRef): Loaded<ChapterContent> {
            chapterCalls += ref
            gate?.await()
            val number = refs.firstOrNull { it.chapterId == ref.chapterId }?.number ?: ref.number
            failChapter[number]?.let { return Loaded.Error(it) }
            return Loaded.Ok(
                ChapterContent(
                    workId, ChapterRef(ref.chapterId, number, "Chapter $number"), "Work $workId", listOf("A"),
                    emptyList(), listOf(Block.Text("body $number", emptyList())), emptyList(), emptyList(),
                )
            )
        }

        override fun prefetch(workId: Long, ref: ChapterRef) { prefetched += ref }
    }

    private class FakeProgressDao : ProgressDao {
        val rows = MutableStateFlow<List<ProgressEntity>>(emptyList())
        val upserts = mutableListOf<ProgressEntity>()
        /** When set, upsert() suspends until it completes (a slow database write). */
        var upsertGate: CompletableDeferred<Unit>? = null
        override fun observeLatest(): Flow<ProgressEntity?> = rows.map { l -> l.maxByOrNull { it.updatedAt } }
        override suspend fun get(workId: Long) = rows.value.firstOrNull { it.workId == workId }
        override suspend fun upsert(p: ProgressEntity) {
            upsertGate?.await()
            upserts += p
            rows.value = rows.value.filterNot { it.workId == p.workId } + p
        }
        override fun observeFor(workIds: List<Long>): Flow<List<ProgressEntity>> =
            rows.map { l -> l.filter { it.workId in workIds } }
    }

    private class MemoryPrefs : DataStore<Preferences> {
        private val flow = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = flow
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(flow.value).also { flow.value = it }
    }

    private companion object { const val WORK = 7L }
}
