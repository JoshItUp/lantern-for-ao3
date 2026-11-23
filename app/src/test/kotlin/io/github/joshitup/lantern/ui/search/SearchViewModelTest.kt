package io.github.joshitup.lantern.ui.search

import io.github.joshitup.lantern.ao3.model.Block
import io.github.joshitup.lantern.ao3.model.SearchQuery
import io.github.joshitup.lantern.ao3.model.WorkBlurb
import io.github.joshitup.lantern.ao3.model.WorkPage
import io.github.joshitup.lantern.ao3.net.FetchResult
import io.github.joshitup.lantern.ao3.parse.Ao3Urls
import io.github.joshitup.lantern.data.FakeFetcher
import io.github.joshitup.lantern.data.Loaded
import io.github.joshitup.lantern.data.SearchRepository
import io.github.joshitup.lantern.data.SearchSource
import io.github.joshitup.lantern.data.db.PresetDao
import io.github.joshitup.lantern.data.db.PresetEntity
import io.github.joshitup.lantern.data.db.ProgressDao
import io.github.joshitup.lantern.data.db.ProgressEntity
import io.github.joshitup.lantern.data.db.ReadMarkDao
import io.github.joshitup.lantern.data.db.ReadMarkEntity
import io.github.joshitup.lantern.data.searchQueryFromJson
import io.github.joshitup.lantern.data.toJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
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
class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var source: FakeSource
    private lateinit var presets: FakePresetDao
    private lateinit var progress: FakeProgressDao
    private lateinit var marks: FakeReadMarkDao

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        source = FakeSource()
        presets = FakePresetDao()
        progress = FakeProgressDao()
        marks = FakeReadMarkDao()
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm() = SearchViewModel(source, presets, progress, marks, dispatcher)

    private fun test(body: suspend TestScope.() -> Unit) = runTest(dispatcher) { body() }

    @Test fun `runSearch loads page 1 and resets list`() = test {
        val q1 = SearchQuery(anyField = "a")
        val q2 = SearchQuery(anyField = "b")
        source.respond(q1, 1, page(1, true, 1, 2))
        source.respond(q2, 1, page(1, false, 3))
        val vm = vm()
        vm.setQuery(q1); vm.runSearch(); advanceUntilIdle()
        assertEquals(listOf(1L, 2L), vm.state.value.works.map { it.workId })
        assertEquals(1, vm.state.value.page)
        assertTrue(vm.state.value.hasNext)
        vm.setQuery(q2); vm.runSearch(); advanceUntilIdle()
        val s = vm.state.value
        assertEquals(listOf(3L), s.works.map { it.workId })
        assertEquals(1, s.page)
        assertFalse(s.hasNext)
        assertFalse(s.loading)
        assertEquals(listOf(q1 to 1, q2 to 1), source.calls)
    }

    @Test fun `searchId bumps on every new search but not on next pages`() = test {
        val q = SearchQuery()
        source.respond(q, 1, page(1, true, 1))
        source.respond(q, 2, page(2, false, 2))
        val vm = vm()
        assertEquals(0, vm.state.value.searchId)
        vm.runSearch(); advanceUntilIdle()
        assertEquals(1, vm.state.value.searchId)
        vm.loadNextPage(); advanceUntilIdle()
        assertEquals(1, vm.state.value.searchId)
        vm.runSearch(); advanceUntilIdle()
        assertEquals(2, vm.state.value.searchId)
    }

    @Test fun `summary blocks converted once per blurb`() = test {
        val q = SearchQuery()
        source.respond(q, 1, WorkPage(listOf(blurb(1, "<p>Hello <b>you</b></p>"), blurb(2, null)), 1, false))
        val vm = vm()
        vm.runSearch(); advanceUntilIdle()
        val blocks = vm.state.value.summaries.getValue(1L)
        assertEquals("Hello you", (blocks.single() as Block.Text).text)
        assertEquals(emptyList<Block>(), vm.state.value.summaries.getValue(2L))
    }

    @Test fun `author and series links built once per blurb`() = test {
        val q = SearchQuery(anyField = "x")
        val w = blurb(1, null).copy(
            authorLinks = listOf(io.github.joshitup.lantern.ao3.model.AuthorLink("A", "a", "A")),
            series = listOf(io.github.joshitup.lantern.ao3.model.SeriesLink(5, "S", 2)),
        )
        source.respond(q, 1, WorkPage(listOf(w), 1, false))
        val vm = vm()
        vm.setQuery(q); vm.runSearch(); advanceUntilIdle()
        val l = vm.state.value.links.getValue(1L)
        assertEquals("https://archiveofourown.org/users/a/works", l.authors.single().url.toString())
        assertEquals("Part 2 of S", l.series.single().label)
    }

    @Test fun `loadNextPage appends and increments page`() = test {
        val q = SearchQuery(anyField = "a")
        source.respond(q, 1, page(1, true, 1, 2))
        source.respond(q, 2, page(2, false, 2, 3))   // 2 repeats: AO3 shifted between requests
        val vm = vm()
        vm.setQuery(q); vm.runSearch(); advanceUntilIdle()
        vm.loadNextPage(); advanceUntilIdle()
        val s = vm.state.value
        assertEquals(listOf(1L, 2L, 3L), s.works.map { it.workId })
        assertEquals(2, s.page)
        assertFalse(s.hasNext)
    }

    @Test fun `loadNextPage ignored while loading or when no next`() = test {
        val q = SearchQuery()
        val gate = CompletableDeferred<Loaded<WorkPage>>()
        source.respond(q, 1, page(1, true, 1))
        source.pending[q to 2] = gate
        val vm = vm()
        vm.runSearch(); advanceUntilIdle()
        vm.loadNextPage(); advanceUntilIdle()
        assertTrue(vm.state.value.loading)
        vm.loadNextPage(); vm.loadNextPage(); advanceUntilIdle()
        assertEquals(1, source.calls.count { it.second == 2 })
        gate.complete(Loaded.Ok(page(2, false, 2))); advanceUntilIdle()
        vm.loadNextPage(); advanceUntilIdle()
        assertEquals(1, source.calls.count { it.second == 2 })
        assertEquals(2, source.calls.size)
    }

    @Test fun `stale page discarded when query changes mid-load`() = test {
        val q1 = SearchQuery(anyField = "a")
        val q2 = SearchQuery(anyField = "b")
        val oldPage2 = CompletableDeferred<Loaded<WorkPage>>()
        val newPage1 = CompletableDeferred<Loaded<WorkPage>>()
        source.respond(q1, 1, page(1, true, 1))
        source.pending[q1 to 2] = oldPage2
        source.pending[q2 to 1] = newPage1
        val vm = vm()
        vm.setQuery(q1); vm.runSearch(); advanceUntilIdle()
        vm.loadNextPage(); advanceUntilIdle()
        vm.setQuery(q2); vm.runSearch(); advanceUntilIdle()
        oldPage2.complete(Loaded.Ok(page(2, true, 99))); advanceUntilIdle()
        assertTrue(vm.state.value.works.none { it.workId == 99L })
        newPage1.complete(Loaded.Ok(page(1, false, 5))); advanceUntilIdle()
        val s = vm.state.value
        assertEquals(listOf(5L), s.works.map { it.workId })
        assertEquals(1, s.page)
        assertFalse(s.loading)
    }

    @Test fun `next page uses the query of the shown results, not an unapplied edit`() = test {
        val q1 = SearchQuery(anyField = "a")
        source.respond(q1, 1, page(1, true, 1))
        source.respond(q1, 2, page(2, false, 2))
        val vm = vm()
        vm.setQuery(q1); vm.runSearch(); advanceUntilIdle()
        vm.setQuery(SearchQuery(anyField = "typing"))
        vm.loadNextPage(); advanceUntilIdle()
        assertEquals(listOf(1L, 2L), vm.state.value.works.map { it.workId })
        assertEquals(q1 to 2, source.calls.last())
    }

    @Test fun `applyPreset sets query and runs`() = test {
        val q = SearchQuery(fandoms = listOf("F"))
        val id = presets.insert(PresetEntity(name = "P", position = 0, queryJson = q.toJson()))
        source.respond(q, 1, page(1, false, 7))
        val vm = vm()
        advanceUntilIdle()
        vm.applyPreset(id); advanceUntilIdle()
        val s = vm.state.value
        assertEquals(q, s.query)
        assertEquals(id, s.activePresetId)
        assertEquals(listOf(7L), s.works.map { it.workId })
        assertEquals(listOf(q to 1), source.calls)
    }

    @Test fun `savePreset stores current query at end position`() = test {
        presets.insert(PresetEntity(name = "A", position = 0, queryJson = "{}"))
        presets.insert(PresetEntity(name = "B", position = 4, queryJson = "{}"))
        val vm = vm()
        advanceUntilIdle()
        val q = SearchQuery(title = "t", characters = listOf("C"))
        vm.setQuery(q)
        vm.savePreset("Mine"); advanceUntilIdle()
        val saved = vm.state.value.presets.last()
        assertEquals("Mine", saved.name)
        assertEquals(5, saved.position)
        assertEquals(q, searchQueryFromJson(saved.queryJson))
        assertEquals(saved.id, vm.state.value.activePresetId)
    }

    @Test fun `rename update delete and reorder presets`() = test {
        val a = presets.insert(PresetEntity(name = "A", position = 0, queryJson = "{}"))
        val b = presets.insert(PresetEntity(name = "B", position = 1, queryJson = "{}"))
        val vm = vm()
        advanceUntilIdle()
        vm.renamePreset(a, "A2")
        vm.setQuery(SearchQuery(title = "x"))
        vm.updatePresetFromCurrent(b)
        advanceUntilIdle()
        vm.reorderPresets(listOf(b, a)); advanceUntilIdle()
        assertEquals(listOf("B", "A2"), vm.state.value.presets.map { it.name })
        assertEquals(SearchQuery(title = "x"), searchQueryFromJson(vm.state.value.presets.first().queryJson))
        vm.deletePreset(b); advanceUntilIdle()
        assertEquals(listOf(a), vm.state.value.presets.map { it.id })
    }

    @Test fun `progress and read marks mapped by work id`() = test {
        val p1 = progressOf(1, updatedAt = 10)
        val p9 = progressOf(9, updatedAt = 50)   // not in results, but latest
        progress.rows.value = listOf(p1, p9)
        marks.rows.value = listOf(ReadMarkEntity(2, 1), ReadMarkEntity(8, 1))
        source.respond(SearchQuery(), 1, page(1, false, 1, 2, 3))
        val vm = vm()
        vm.runSearch(); advanceUntilIdle()
        val s = vm.state.value
        assertEquals(mapOf(1L to p1), s.progressByWork)
        assertEquals(setOf(2L), s.readMarks)
        assertEquals(p9, s.continueReading)
        val p3 = progressOf(3, updatedAt = 99)
        progress.rows.value = progress.rows.value + p3
        advanceUntilIdle()
        assertEquals(setOf(1L, 3L), vm.state.value.progressByWork.keys)
        assertEquals(p3, vm.state.value.continueReading)
    }

    @Test fun `error surfaces in state and keeps existing works`() = test {
        val q = SearchQuery()
        source.respond(q, 1, page(1, true, 1, 2))
        source.results[q to 2] = Loaded.Error(FetchResult.Blocked)
        val vm = vm()
        vm.runSearch(); advanceUntilIdle()
        vm.loadNextPage(); advanceUntilIdle()
        val s = vm.state.value
        assertEquals(FetchResult.Blocked, s.error)
        assertEquals(listOf(1L, 2L), s.works.map { it.workId })
        assertEquals(1, s.page)
        assertFalse(s.loading)
        // A failed page is not re-requested by auto paging; only retry() asks again.
        vm.loadNextPage(); advanceUntilIdle()
        assertEquals(2, source.calls.size)
        source.respond(q, 2, page(2, false, 3))
        vm.retry(); advanceUntilIdle()
        assertNull(vm.state.value.error)
        assertEquals(listOf(1L, 2L, 3L), vm.state.value.works.map { it.workId })
    }

    @Test fun `autocomplete skips short terms and returns suggestions`() = test {
        source.suggestions["fandom" to "har"] = listOf("Harbor Lights")
        val vm = vm()
        var got: List<String>? = null
        vm.autocomplete("fandom", "h").collect { got = it }
        assertEquals(emptyList<String>(), got)
        vm.autocomplete("fandom", "har").collect { got = it }
        assertEquals(listOf("Harbor Lights"), got)
    }

    @Test fun `autocomplete cache hit emits at once without a request`() = test {
        val f = FakeFetcher()
        f.ok(Ao3Urls.autocomplete("fandom", "har"), """[{"id":"Harbor Lights","name":"Harbor Lights"}]""")
        val repo = SearchRepository(f, dispatcher)
        val vm = SearchViewModel(repo, presets, progress, marks, dispatcher)
        vm.autocomplete("fandom", "har").toList()
        val start = currentTime
        assertEquals(listOf(listOf("Harbor Lights")), vm.autocomplete("fandom", "Har").toList())
        assertEquals(start, currentTime)
        assertEquals(1, f.calls.size)
    }

    @Test fun `complete prefix result is filtered locally with no request`() = test {
        val f = FakeFetcher()
        f.ok(Ao3Urls.autocomplete("fandom", "ha"), """[{"id":"Harbor Lights","name":"Harbor Lights"},{"id":"Hanover","name":"Hanover"}]""")
        val repo = SearchRepository(f, dispatcher)
        val vm = SearchViewModel(repo, presets, progress, marks, dispatcher)
        vm.autocomplete("fandom", "ha").toList()
        assertEquals(listOf(listOf("Harbor Lights")), vm.autocomplete("fandom", "harb").toList())
        assertEquals(1, f.calls.size)
    }

    @Test fun `incomplete prefix shows local results then requests after the debounce`() = test {
        val f = FakeFetcher()
        val ten = (1..10).joinToString(",", "[", "]") { """{"id":"Harbor $it","name":"Harbor $it"}""" }
        f.ok(Ao3Urls.autocomplete("fandom", "ha"), ten)
        f.ok(Ao3Urls.autocomplete("fandom", "harbor 1"), """[{"id":"Harbor 1","name":"Harbor 1"},{"id":"Harbor 11","name":"Harbor 11"}]""")
        val repo = SearchRepository(f, dispatcher)
        val vm = SearchViewModel(repo, presets, progress, marks, dispatcher)
        vm.autocomplete("fandom", "ha").toList()
        val emitted = mutableListOf<Pair<Long, List<String>>>()
        val start = currentTime
        vm.autocomplete("fandom", "harbor 1").collect { emitted += (currentTime - start) to it }
        assertEquals(
            listOf(0L to listOf("Harbor 1", "Harbor 10"), 150L to listOf("Harbor 1", "Harbor 11")),
            emitted,
        )
        assertEquals(2, f.calls.size)
    }

    @Test fun `unexpected search exception becomes Failed and stops loading`() = test {
        val boom = IllegalStateException("parser broke")
        source.throwOn[SearchQuery() to 1] = boom
        val vm = vm()
        vm.runSearch(); advanceUntilIdle()
        val s = vm.state.value
        assertFalse(s.loading)
        assertEquals(FetchResult.Failed(boom), s.error)
        source.throwOn.clear()
        source.respond(SearchQuery(), 1, page(1, false, 4))
        vm.retry(); advanceUntilIdle()
        assertEquals(listOf(4L), vm.state.value.works.map { it.workId })
    }

    // --- helpers and fakes ---

    private fun progressOf(id: Long, updatedAt: Long) = ProgressEntity(
        workId = id, workTitle = "W$id", chapterId = null, chapterNumber = 2, chaptersTotal = 5,
        blockIndex = 0, blockOffsetPx = 0, updatedAt = updatedAt,
    )

    private fun page(n: Int, hasNext: Boolean, vararg ids: Long) =
        WorkPage(ids.map { blurb(it, "<p>S$it</p>") }, n, hasNext)

    private fun blurb(id: Long, summary: String?) = WorkBlurb(
        workId = id, title = "T$id", authors = listOf("A"), fandoms = emptyList(), rating = null,
        warnings = emptyList(), categories = emptyList(), complete = null, relationships = emptyList(),
        characters = emptyList(), freeforms = emptyList(), summaryHtml = summary, language = null, words = null,
        chaptersPosted = null, chaptersTotal = null, kudos = null, hits = null, bookmarks = null,
        comments = null, updated = null,
    )

    private class FakeSource : SearchSource {
        val calls = mutableListOf<Pair<SearchQuery, Int>>()
        val results = mutableMapOf<Pair<SearchQuery, Int>, Loaded<WorkPage>>()
        val pending = mutableMapOf<Pair<SearchQuery, Int>, CompletableDeferred<Loaded<WorkPage>>>()
        val suggestions = mutableMapOf<Pair<String, String>, List<String>>()
        val throwOn = mutableMapOf<Pair<SearchQuery, Int>, Exception>()

        fun respond(q: SearchQuery, n: Int, p: WorkPage) { results[q to n] = Loaded.Ok(p) }

        override suspend fun search(q: SearchQuery, page: Int): Loaded<WorkPage> {
            calls += q to page
            throwOn[q to page]?.let { throw it }
            pending[q to page]?.let { return it.await() }
            return results[q to page] ?: Loaded.Error(FetchResult.NotFound)
        }

        override suspend fun autocomplete(kind: String, term: String): List<String> =
            suggestions[kind to term] ?: emptyList()
    }

    private class FakePresetDao : PresetDao {
        val rows = MutableStateFlow<List<PresetEntity>>(emptyList())
        private var nextId = 1L
        override fun observeAll(): Flow<List<PresetEntity>> =
            rows.map { l -> l.sortedWith(compareBy<PresetEntity>({ it.position }, { it.id })) }
        override suspend fun insert(p: PresetEntity): Long {
            val id = nextId++
            rows.value = rows.value + p.copy(id = id)
            return id
        }
        override suspend fun update(p: PresetEntity) { rows.value = rows.value.map { if (it.id == p.id) p else it } }
        override suspend fun delete(id: Long) { rows.value = rows.value.filterNot { it.id == id } }
        override suspend fun setPosition(id: Long, position: Int) {
            rows.value = rows.value.map { if (it.id == id) it.copy(position = position) else it }
        }
    }

    private class FakeProgressDao : ProgressDao {
        val rows = MutableStateFlow<List<ProgressEntity>>(emptyList())
        override fun observeLatest(): Flow<ProgressEntity?> = rows.map { l -> l.maxByOrNull { it.updatedAt } }
        override suspend fun get(workId: Long) = rows.value.firstOrNull { it.workId == workId }
        override suspend fun upsert(p: ProgressEntity) {
            rows.value = rows.value.filterNot { it.workId == p.workId } + p
        }
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
