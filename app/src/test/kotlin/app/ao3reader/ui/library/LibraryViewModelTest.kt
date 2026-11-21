package app.ao3reader.ui.library

import app.ao3reader.ao3.model.Block
import app.ao3reader.ao3.model.WorkBlurb
import app.ao3reader.ao3.net.FetchResult
import app.ao3reader.ao3.parse.BookmarkExtra
import app.ao3reader.ao3.parse.LibraryPage
import app.ao3reader.ao3.parse.ReadingExtra
import app.ao3reader.ao3.parse.Subscription
import app.ao3reader.ao3.parse.SubscriptionPage
import app.ao3reader.data.LibrarySection
import app.ao3reader.data.LibrarySection.BOOKMARKS
import app.ao3reader.data.LibrarySection.HISTORY
import app.ao3reader.data.LibrarySection.MARKED_FOR_LATER
import app.ao3reader.data.LibrarySection.SUBSCRIPTIONS
import app.ao3reader.data.Loaded
import app.ao3reader.data.NotSignedInException
import app.ao3reader.data.db.ProgressDao
import app.ao3reader.data.db.ProgressEntity
import app.ao3reader.data.db.ReadMarkDao
import app.ao3reader.data.db.ReadMarkEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var source: FakeLibrarySource
    private lateinit var progress: FakeProgressDao
    private lateinit var marks: FakeReadMarkDao
    private val downloaded = MutableStateFlow<Set<Long>>(emptySet())

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        source = FakeLibrarySource()
        progress = FakeProgressDao()
        marks = FakeReadMarkDao()
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm() = LibraryViewModel(source, progress, marks, dispatcher, downloaded)

    private fun test(body: suspend TestScope.() -> Unit) = runTest(dispatcher) { body() }

    private fun LibraryViewModel.section(s: LibrarySection) = state.value.sections.getValue(s)

    @Test fun `cached page 1 shows first, then the network page replaces it`() = test {
        source.cache[HISTORY] = page(1, true, 1, 2)
        val network = CompletableDeferred<Loaded<LibraryPage>>()
        source.pending[HISTORY to 1] = network
        val vm = vm()
        advanceUntilIdle()
        var s = vm.section(HISTORY)
        assertEquals(listOf(1L, 2L), s.works.map { it.workId })
        assertTrue(s.refreshing)
        network.complete(Loaded.Ok(page(1, false, 3, 1)))
        advanceUntilIdle()
        s = vm.section(HISTORY)
        assertEquals(listOf(3L, 1L), s.works.map { it.workId })
        assertFalse(s.refreshing)
        assertFalse(s.loading)
        assertFalse(s.hasNext)
        assertEquals(listOf(HISTORY to 1), source.calls)
    }

    @Test fun `without a cache the first page loads from the network`() = test {
        source.respond(HISTORY, 1, page(1, true, 1))
        val vm = vm()
        advanceUntilIdle()
        assertEquals(listOf(1L), vm.section(HISTORY).works.map { it.workId })
        assertEquals("S1", (vm.section(HISTORY).summaries.getValue(1L).single() as Block.Text).text)
    }

    @Test fun `paging appends one page at a time and dedupes`() = test {
        source.respond(HISTORY, 1, page(1, true, 1, 2))
        val p2 = CompletableDeferred<Loaded<LibraryPage>>()
        source.pending[HISTORY to 2] = p2
        val vm = vm()
        advanceUntilIdle()
        vm.loadNextPage(HISTORY)
        vm.loadNextPage(HISTORY)
        advanceUntilIdle()
        assertTrue(vm.section(HISTORY).loading)
        assertEquals(listOf(HISTORY to 1, HISTORY to 2), source.calls)
        p2.complete(Loaded.Ok(page(2, false, 2, 3)))
        advanceUntilIdle()
        val s = vm.section(HISTORY)
        assertEquals(listOf(1L, 2L, 3L), s.works.map { it.workId })
        assertEquals(2, s.page)
        assertFalse(s.hasNext)
        vm.loadNextPage(HISTORY)
        advanceUntilIdle()
        assertEquals(2, source.calls.size)
    }

    @Test fun `no next page while page 1 is refreshing`() = test {
        source.cache[HISTORY] = page(1, true, 1)
        source.pending[HISTORY to 1] = CompletableDeferred()
        val vm = vm()
        advanceUntilIdle()
        vm.loadNextPage(HISTORY)
        advanceUntilIdle()
        assertEquals(listOf(HISTORY to 1), source.calls)
    }

    @Test fun `switching tabs keeps each tab's state and loads a tab only once`() = test {
        source.respond(HISTORY, 1, page(1, true, 1, 2))
        source.respond(HISTORY, 2, page(2, false, 3))
        source.respond(BOOKMARKS, 1, page(1, false, 7))
        val vm = vm()
        advanceUntilIdle()
        vm.loadNextPage(HISTORY); advanceUntilIdle()
        vm.select(LibraryTab.Ao3(BOOKMARKS)); advanceUntilIdle()
        assertEquals(LibraryTab.Ao3(BOOKMARKS), vm.state.value.selected)
        assertEquals(listOf(7L), vm.section(BOOKMARKS).works.map { it.workId })
        vm.select(LibraryTab.Ao3(HISTORY)); advanceUntilIdle()
        vm.select(LibraryTab.Ao3(BOOKMARKS)); advanceUntilIdle()
        assertEquals(listOf(1L, 2L, 3L), vm.section(HISTORY).works.map { it.workId })
        assertEquals(2, vm.section(HISTORY).page)
        assertEquals(listOf(HISTORY to 1, HISTORY to 2, BOOKMARKS to 1), source.calls)
        // Untouched tabs never load.
        assertTrue(vm.section(MARKED_FOR_LATER).works.isEmpty())
    }

    @Test fun `a failed next page keeps the works and retry repeats it`() = test {
        source.respond(HISTORY, 1, page(1, true, 1, 2))
        source.results[HISTORY to 2] = Loaded.Error(FetchResult.Blocked)
        val vm = vm()
        advanceUntilIdle()
        vm.loadNextPage(HISTORY); advanceUntilIdle()
        var s = vm.section(HISTORY)
        assertEquals(listOf(1L, 2L), s.works.map { it.workId })
        assertEquals(FetchResult.Blocked, s.error)
        assertEquals(2, s.failedPage)
        assertFalse(s.loading)
        vm.loadNextPage(HISTORY); advanceUntilIdle()   // blocked by the error
        assertEquals(2, source.calls.size)
        source.respond(HISTORY, 2, page(2, false, 3))
        vm.retry(HISTORY); advanceUntilIdle()
        s = vm.section(HISTORY)
        assertEquals(listOf(1L, 2L, 3L), s.works.map { it.workId })
        assertNull(s.error)
    }

    @Test fun `a failed refresh keeps the cached works`() = test {
        source.cache[BOOKMARKS] = page(1, true, 4, 5)
        source.results[BOOKMARKS to 1] = Loaded.Error(FetchResult.RateLimited(5))
        val vm = vm()
        vm.select(LibraryTab.Ao3(BOOKMARKS))
        advanceUntilIdle()
        val s = vm.section(BOOKMARKS)
        assertEquals(listOf(4L, 5L), s.works.map { it.workId })
        assertEquals(FetchResult.RateLimited(5), s.error)
        assertEquals(1, s.failedPage)
        assertFalse(s.refreshing)
    }

    @Test fun `pull to refresh replaces the list with page 1`() = test {
        source.respond(HISTORY, 1, page(1, true, 1))
        source.respond(HISTORY, 2, page(2, false, 2))
        val vm = vm()
        advanceUntilIdle()
        vm.loadNextPage(HISTORY); advanceUntilIdle()
        source.respond(HISTORY, 1, page(1, true, 9, 1))
        vm.refresh(HISTORY)
        assertTrue(vm.section(HISTORY).pulled)
        advanceUntilIdle()
        val s = vm.section(HISTORY)
        assertEquals(listOf(9L, 1L), s.works.map { it.workId })
        assertEquals(1, s.page)
        assertFalse(s.pulled)
    }

    @Test fun `history and bookmark extras are precomputed`() = test {
        source.respond(HISTORY, 1, LibraryPage(
            listOf(blurb(1), blurb(2), blurb(3)),
            mapOf(
                1L to ReadingExtra(1, "12 Nov 2026", 3, true),
                2L to ReadingExtra(2, "1 Jan 2025", 1, false),
                3L to ReadingExtra(3, null, null, false),
            ),
            emptyMap(), 1, false,
        ))
        source.respond(BOOKMARKS, 1, LibraryPage(
            listOf(blurb(5)), emptyMap(),
            mapOf(5L to BookmarkExtra(5, "<p>My <i>note</i></p>", listOf("fluff", "re-read"), isPrivate = true, isRec = true)),
            1, false,
        ))
        val vm = vm()
        advanceUntilIdle()
        val h = vm.section(HISTORY).extras
        assertEquals("Visited 12 Nov 2026 · 3 visits", h.getValue(1L).visited)
        assertTrue(h.getValue(1L).updateAvailable)
        assertEquals("Visited 1 Jan 2025 · 1 visit", h.getValue(2L).visited)
        assertNull(h[3L]?.visited)
        vm.select(LibraryTab.Ao3(BOOKMARKS)); advanceUntilIdle()
        val b = vm.section(BOOKMARKS).extras.getValue(5L)
        assertEquals("My note", (b.notes.single() as Block.Text).text)
        assertEquals(listOf("fluff", "re-read"), b.tags)
        assertEquals(listOf("Private", "Rec"), b.labels)
    }

    @Test fun `subscription rows map kinds and only allow AO3 links`() = test {
        source.subs[1] = Loaded.Ok(SubscriptionPage(listOf(
            Subscription.Work(10, "A work", "Someone"),
            Subscription.Series("https://archiveofourown.org/series/5".toHttpUrl(), "A series"),
            Subscription.User("https://archiveofourown.org/users/bob".toHttpUrl(), "bob"),
            Subscription.User("http://evil.example/users/x".toHttpUrl(), "x"),
            Subscription.Work(10, "A work", "Someone"),
        ), 1, false))
        val vm = vm()
        vm.select(LibraryTab.Ao3(SUBSCRIPTIONS)); advanceUntilIdle()
        val rows = vm.section(SUBSCRIPTIONS).subs
        assertEquals(4, rows.size)
        assertEquals(SubRow("work:10", "A work", "Someone", 10, null), rows[0])
        assertEquals(SubRow("https://archiveofourown.org/series/5", "A series", "Series", null, "https://archiveofourown.org/series/5".toHttpUrl()), rows[1])
        assertEquals("Author", rows[2].detail)
        // Normalized to the author's works list (what the in-app list loads).
        assertEquals("https://archiveofourown.org/users/bob/works", rows[2].url.toString())
        assertNull(rows[3].url)
    }

    @Test fun signedOut_selectsDownloads_andAo3TabsShowSignIn() = test {
        source.account.value = null
        source.respond(HISTORY, 1, page(1, false, 1))
        val vm = vm()
        advanceUntilIdle()
        assertFalse(vm.state.value.signedIn)
        assertEquals(LibraryTab.Downloads, vm.state.value.selected)
        // An AO3 tab can be selected signed out; it shows the sign-in prompt and loads nothing.
        vm.select(LibraryTab.Ao3(HISTORY)); advanceUntilIdle()
        assertEquals(LibraryTab.Ao3(HISTORY), vm.state.value.selected)
        assertFalse(vm.state.value.signedIn)
        assertTrue(source.calls.isEmpty())
        // Signing in keeps the selected tab and loads it.
        source.account.value = "me"; advanceUntilIdle()
        assertEquals(LibraryTab.Ao3(HISTORY), vm.state.value.selected)
        assertEquals(listOf(1L), vm.section(HISTORY).works.map { it.workId })
    }

    @Test fun `signing in on the downloads tab stays there and loads nothing`() = test {
        source.account.value = null
        source.respond(HISTORY, 1, page(1, false, 1))
        val vm = vm()
        advanceUntilIdle()
        source.account.value = "me"; advanceUntilIdle()
        assertTrue(vm.state.value.signedIn)
        assertEquals(LibraryTab.Downloads, vm.state.value.selected)
        assertTrue(source.calls.isEmpty())
        vm.select(LibraryTab.Ao3(HISTORY)); advanceUntilIdle()
        assertEquals(listOf(HISTORY to 1), source.calls)
    }

    @Test fun `signed in opens on history`() = test {
        source.respond(HISTORY, 1, page(1, false, 1))
        val vm = vm()
        advanceUntilIdle()
        assertEquals(LibraryTab.Ao3(HISTORY), vm.state.value.selected)
    }

    @Test fun `downloaded ids reach the state`() = test {
        val vm = vm()
        advanceUntilIdle()
        downloaded.value = setOf(4L, 9L)
        advanceUntilIdle()
        assertEquals(setOf(4L, 9L), vm.state.value.downloadedIds)
    }

    @Test fun `another account resets the lists`() = test {
        source.respond(HISTORY, 1, page(1, false, 1))
        val vm = vm()
        advanceUntilIdle()
        source.respond(HISTORY, 1, page(1, false, 2))
        source.account.value = "other"; advanceUntilIdle()
        assertEquals(listOf(2L), vm.section(HISTORY).works.map { it.workId })
        assertEquals(2, source.calls.size)
    }

    @Test fun `losing the account resets every tab and signing back in reloads`() = test {
        source.respond(HISTORY, 1, page(1, false, 1))
        source.respond(BOOKMARKS, 1, page(1, false, 5))
        val vm = vm()
        advanceUntilIdle()
        vm.select(LibraryTab.Ao3(BOOKMARKS)); advanceUntilIdle()
        source.account.value = null; advanceUntilIdle()
        assertFalse(vm.state.value.signedIn)
        assertTrue(vm.section(HISTORY).isEmpty)
        assertTrue(vm.section(BOOKMARKS).isEmpty)
        // Nothing loads while there is no account.
        vm.refresh(BOOKMARKS); vm.retry(BOOKMARKS); vm.loadNextPage(BOOKMARKS); advanceUntilIdle()
        assertEquals(2, source.calls.size)
        source.account.value = "me"; advanceUntilIdle()
        assertEquals(listOf(5L), vm.section(BOOKMARKS).works.map { it.workId })
        assertEquals(listOf(HISTORY to 1, BOOKMARKS to 1, BOOKMARKS to 1), source.calls)
        // A tab never shown since then starts again when selected.
        vm.select(LibraryTab.Ao3(HISTORY)); advanceUntilIdle()
        assertEquals(HISTORY to 1, source.calls.last())
    }

    @Test fun `a NotSignedIn error while an account acts is retryable`() = test {
        source.results[HISTORY to 1] = Loaded.Error(FetchResult.Failed(NotSignedInException()))
        val vm = vm()
        advanceUntilIdle()
        assertTrue(vm.state.value.signedIn)
        assertTrue(isNotSignedIn(vm.section(HISTORY).error!!))
        source.respond(HISTORY, 1, page(1, false, 1))
        vm.retry(HISTORY); advanceUntilIdle()
        assertEquals(listOf(1L), vm.section(HISTORY).works.map { it.workId })
    }

    @Test fun `a page 1 replace bumps the replace id`() = test {
        source.cache[HISTORY] = page(1, true, 1)
        source.respond(HISTORY, 1, page(1, false, 2, 1))
        val vm = vm()
        advanceUntilIdle()
        val first = vm.section(HISTORY).replaceId
        assertTrue(first > 0)
        vm.refresh(HISTORY); advanceUntilIdle()
        assertTrue(vm.section(HISTORY).replaceId > first)
    }

    @Test fun `badges follow progress and read marks of shown works`() = test {
        source.respond(HISTORY, 1, page(1, false, 1, 2))
        val vm = vm()
        advanceUntilIdle()
        progress.rows.value = listOf(ProgressEntity(1, "T1", 11, 3, 5, 0, 0, 1))
        marks.rows.value = listOf(ReadMarkEntity(2, 1))
        advanceUntilIdle()
        assertEquals(3, vm.state.value.progressByWork.getValue(1L).chapterNumber)
        assertEquals(setOf(2L), vm.state.value.readMarks)
    }

    @Test fun `not signed in errors are recognised`() {
        assertTrue(isNotSignedIn(FetchResult.Failed(NotSignedInException())))
        assertFalse(isNotSignedIn(FetchResult.Blocked))
        // Only shown while an account acts (no account shows the prompt): a plain, retryable error.
        assertEquals("Couldn't load results.", libraryErrorMessage(FetchResult.Failed(NotSignedInException())))
        assertEquals("AO3 is blocking requests right now.", libraryErrorMessage(FetchResult.Blocked))
    }

    @Test fun `library cards get author and series links`() = test {
        val w = blurb(1).copy(
            authorLinks = listOf(app.ao3reader.ao3.model.AuthorLink("A", "a", "A")),
            series = listOf(app.ao3reader.ao3.model.SeriesLink(5, "S", null)),
        )
        source.respond(HISTORY, 1, LibraryPage(listOf(w), emptyMap(), emptyMap(), 1, false))
        val vm = vm()
        advanceUntilIdle()
        val l = vm.section(HISTORY).links.getValue(1L)
        assertEquals("https://archiveofourown.org/users/a/works", l.authors.single().url.toString())
        assertEquals("https://archiveofourown.org/series/5", l.series.single().url.toString())
    }

    private fun page(n: Int, hasNext: Boolean, vararg ids: Long) =
        LibraryPage(ids.map { blurb(it) }, emptyMap(), emptyMap(), n, hasNext)

    private fun blurb(id: Long) = WorkBlurb(
        workId = id, title = "T$id", authors = listOf("A"), fandoms = emptyList(), rating = null,
        warnings = emptyList(), categories = emptyList(), complete = null, relationships = emptyList(),
        characters = emptyList(), freeforms = emptyList(), summaryHtml = "<p>S$id</p>", language = null, words = null,
        chaptersPosted = null, chaptersTotal = null, kudos = null, hits = null, bookmarks = null,
        comments = null, updated = null,
    )

    private class FakeLibrarySource : LibrarySource {
        override val account = MutableStateFlow<String?>("me")
        val cache = mutableMapOf<LibrarySection, LibraryPage>()
        val calls = mutableListOf<Pair<LibrarySection, Int>>()
        val results = mutableMapOf<Pair<LibrarySection, Int>, Loaded<LibraryPage>>()
        val pending = mutableMapOf<Pair<LibrarySection, Int>, CompletableDeferred<Loaded<LibraryPage>>>()
        val subs = mutableMapOf<Int, Loaded<SubscriptionPage>>()

        fun respond(s: LibrarySection, n: Int, p: LibraryPage) {
            pending.remove(s to n)
            results[s to n] = Loaded.Ok(p)
        }

        override fun cached(section: LibrarySection, page: Int): Flow<LibraryPage?> =
            flowOf(if (page == 1) cache[section] else null)

        override suspend fun load(section: LibrarySection, page: Int): Loaded<LibraryPage> {
            calls += section to page
            pending[section to page]?.let { return it.await() }
            return results[section to page] ?: Loaded.Error(FetchResult.NotFound)
        }

        override suspend fun subscriptions(page: Int): Loaded<SubscriptionPage> {
            calls += SUBSCRIPTIONS to page
            return subs[page] ?: Loaded.Error(FetchResult.NotFound)
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
