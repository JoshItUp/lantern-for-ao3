package app.ao3reader.data.download

import app.ao3reader.ao3.model.Block
import app.ao3reader.ao3.model.ChapterContent
import app.ao3reader.ao3.model.ChapterRef
import app.ao3reader.ao3.net.FetchResult
import app.ao3reader.ao3.parse.Ao3Urls
import app.ao3reader.data.AuthedFetch
import app.ao3reader.data.CHAPTER_HTML
import app.ao3reader.data.ChapterCache
import app.ao3reader.data.FakeFetcher
import app.ao3reader.data.Loaded
import app.ao3reader.data.WorkRepository
import app.ao3reader.data.db.DownloadChapterEntity
import app.ao3reader.data.db.DownloadDao
import app.ao3reader.data.db.DownloadEntity
import app.ao3reader.data.db.DownloadJob
import app.ao3reader.data.db.SourceKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadAwareChapterSourceTest {
    @get:Rule val tmp = TemporaryFolder()

    private val work = 7L
    private fun storedRef(n: Int) = ChapterRef(100L + n, n, "Stored $n")

    private fun stored(n: Int) = ChapterContent(
        workId = work, ref = storedRef(n), workTitle = "W", authors = listOf("a"),
        beginNotes = emptyList(), body = listOf(Block.Text("stored $n", emptyList())), endNotes = emptyList(), summary = emptyList(),
    )

    private fun text(c: ChapterContent) = (c.body[0] as Block.Text).text

    private fun navigateHtml(vararg ids: Long) =
        """<ol class="chapter index">""" +
            ids.mapIndexed { i, id -> """<li><a href="/works/$work/chapters/$id">${i + 1}. Online $id</a></li>""" }.joinToString("") +
            "</ol>"

    private class FakeAuthed(val fetcher: FakeFetcher = FakeFetcher(), var acting: Boolean = false) : AuthedFetch {
        override fun isActing() = acting
        override suspend fun get(url: okhttp3.HttpUrl, interactive: Boolean, forceNetwork: Boolean) =
            fetcher.get(url, null, interactive, false, forceNetwork)
    }

    private inner class Env(val scope: TestScope) {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val bg = CoroutineScope(SupervisorJob() + dispatcher)
        val fetcher = FakeFetcher()
        val authed = FakeAuthed()
        val dao = FakeDownloadDao()
        val root: File = tmp.newFolder()
        val store = DownloadStore(root, dao, Json, dispatcher) { 1000L }
        val downloads = DownloadRepository(dao, store, {}, { 1000L })
        val online = WorkRepository(
            fetcher, ChapterCache(tmp.newFolder()), bg, dispatcher, dispatcher, clock = { 1_000_000L }, authed = authed,
        )
        val source = DownloadAwareChapterSource(online, dao, store, downloads, bg, authed::isActing)

        suspend fun download(count: Int) {
            downloads.startup()
            val w = store.begin(work)
            (1..count).forEach { w.write(it - 1, stored(it)) }
            w.commit(null, SourceKind.FULL_WORK_PAGE, (1..count).map(::storedRef), count)
        }

        fun close() = bg.cancel()
    }

    private fun test(body: suspend Env.() -> Unit) = runTest {
        val env = Env(this)
        try { env.body() } finally { env.close() }
    }

    @Test fun storedChapter_readsFromStore_noFetch() = test {
        download(3)
        val r = source.chapter(work, storedRef(2)) as Loaded.Ok
        scope.advanceUntilIdle()
        assertEquals("stored 2", text(r.value))
        assertTrue(fetcher.calls.isEmpty())
        assertTrue(authed.fetcher.calls.isEmpty())
    }

    @Test fun storedChapter_signedIn_visitsInBackground() = test {
        download(3)
        authed.acting = true
        authed.fetcher.ok(Ao3Urls.chapter(work, 102), CHAPTER_HTML)
        val r = source.chapter(work, storedRef(2)) as Loaded.Ok
        assertEquals("stored 2", text(r.value))
        scope.advanceUntilIdle()
        assertEquals(listOf(Ao3Urls.chapter(work, 102).toString()), authed.fetcher.calls)
        assertEquals(listOf(true), authed.fetcher.forceNetworks)
        assertTrue(fetcher.calls.isEmpty())
        // The visit never rewrites the download.
        assertEquals("stored 2", text(store.readChapter(work, 1)!!))
    }

    @Test fun storedChapter_signedOut_noVisit() = test {
        download(3)
        source.chapter(work, storedRef(2))
        scope.advanceUntilIdle()
        assertTrue(authed.fetcher.calls.isEmpty())
        assertTrue(fetcher.calls.isEmpty())
    }

    @Test fun storedChapter_matchedByNumberWhenIdUnknown() = test {
        download(3)
        val r = source.chapter(work, ChapterRef(null, 3, "")) as Loaded.Ok
        assertEquals("stored 3", text(r.value))
        assertTrue(fetcher.calls.isEmpty())
    }

    @Test fun chapters_returnsStoredImmediately_thenEmitsMergedOnline() = test {
        download(3)
        fetcher.ok(Ao3Urls.navigate(work), navigateHtml(101, 102, 103, 104))
        val gate = CompletableDeferred<Unit>().also { fetcher.gate = it }
        val r = source.chapters(work) as Loaded.Ok
        assertEquals((1..3).map(::storedRef), r.value)
        scope.runCurrent()
        assertNull(withTimeoutOrNull(1) { source.listUpdates(work).first() })
        gate.complete(Unit)
        val merged = source.listUpdates(work).first()
        assertEquals((1..3).map(::storedRef) + ChapterRef(104, 4, "Online 104"), merged)
        scope.advanceUntilIdle()
        assertEquals(4, dao.get(work)!!.chaptersPostedRemote)
    }

    @Test fun merge_appendsOnlyNewIds() = test {
        download(3)
        // AO3 renamed chapter 2 and added 104 and 105: stored refs stay as stored, only new ids are appended.
        fetcher.ok(Ao3Urls.navigate(work), navigateHtml(101, 102, 103, 104, 105))
        source.chapters(work)
        val merged = source.listUpdates(work).first()
        assertEquals(
            (1..3).map(::storedRef) + listOf(ChapterRef(104, 4, "Online 104"), ChapterRef(105, 5, "Online 105")),
            merged,
        )
    }

    @Test fun listCheckNoNewChapters_emitsStoredList() = test {
        download(3)
        fetcher.ok(Ao3Urls.navigate(work), navigateHtml(101, 102, 103))
        source.chapters(work)
        scope.advanceUntilIdle()
        assertEquals((1..3).map(::storedRef), withTimeoutOrNull(1) { source.listUpdates(work).first() })
        assertEquals(3, dao.get(work)!!.chaptersPostedRemote)
    }

    @Test fun listCheckFailed_emitsStoredList() = test {
        download(3)
        fetcher.responses[Ao3Urls.navigate(work).toString()] = FetchResult.Failed(java.io.IOException("offline"))
        source.chapters(work)
        scope.advanceUntilIdle()
        assertEquals((1..3).map(::storedRef), withTimeoutOrNull(1) { source.listUpdates(work).first() })
    }

    @Test fun merge_nullIdStored_matchesByNumber() = test {
        // A one-shot is stored without a chapter id; AO3's list gives its chapter an id.
        downloads.startup()
        val oneShot = ChapterRef(null, 1, "Only")
        store.begin(work).apply { write(0, stored(1)); commit(null, SourceKind.FULL_WORK_PAGE, listOf(oneShot), 1) }
        fetcher.ok(Ao3Urls.navigate(work), navigateHtml(555))
        source.chapters(work)
        scope.advanceUntilIdle()
        assertEquals(listOf(oneShot), withTimeoutOrNull(1) { source.listUpdates(work).first() })
    }

    @Test fun notStored_online_delegates() = test {
        download(3)
        fetcher.ok(Ao3Urls.chapter(work, 104), CHAPTER_HTML)
        val r = source.chapter(work, ChapterRef(104, 4, "Online 104")) as Loaded.Ok
        assertEquals("My Work", r.value.workTitle)
        assertEquals(listOf(Ao3Urls.chapter(work, 104).toString()), fetcher.calls)
    }

    @Test fun notStored_offline_notDownloadedError() = test {
        download(3)
        fetcher.responses[Ao3Urls.chapter(work, 104).toString()] = FetchResult.Failed(java.io.IOException("offline"))
        val r = source.chapter(work, ChapterRef(104, 4, "Online 104")) as Loaded.Error
        assertTrue((r.result as FetchResult.Failed).cause is NotDownloadedException)
        fetcher.responses[Ao3Urls.chapter(work, 104).toString()] = FetchResult.Blocked
        val b = source.chapter(work, ChapterRef(104, 4, "Online 104")) as Loaded.Error
        assertTrue((b.result as FetchResult.Failed).cause is NotDownloadedException)
        // Stored chapters still read at once.
        assertEquals("stored 1", text((source.chapter(work, storedRef(1)) as Loaded.Ok).value))
    }

    @Test fun notStored_restrictedSignedOut_keepsNotAChapterPage() = test {
        download(3)
        fetcher.ok(Ao3Urls.chapter(work, 104), "<html><body>Please log in.</body></html>")
        val r = source.chapter(work, ChapterRef(104, 4, "Online 104")) as Loaded.Error
        assertTrue((r.result as FetchResult.Failed).cause is app.ao3reader.data.NotAChapterPageException)
    }

    @Test fun corruptStored_fallsBackOnline() = test {
        download(3)
        File(root, "$work/1.json").writeText("{not json")
        fetcher.ok(Ao3Urls.chapter(work, 102), CHAPTER_HTML)
        val r = source.chapter(work, storedRef(2)) as Loaded.Ok
        assertEquals("My Work", r.value.workTitle)
        assertEquals(listOf(Ao3Urls.chapter(work, 102).toString()), fetcher.calls)
    }

    @Test fun prefetch_storedIsNoop_notStoredPrefetches() = test {
        download(3)
        fetcher.ok(Ao3Urls.chapter(work, 104), CHAPTER_HTML)
        source.prefetch(work, storedRef(2))
        scope.advanceUntilIdle()
        assertTrue(fetcher.calls.isEmpty())
        source.prefetch(work, ChapterRef(104, 4, ""))
        scope.advanceUntilIdle()
        assertEquals(listOf(Ao3Urls.chapter(work, 104).toString()), fetcher.calls)
    }

    @Test fun noCopy_fullyDelegates() = test {
        downloads.startup()
        // A queued row without a copy counts as not downloaded.
        dao.upsert(DownloadEntity(work, "W", "a", null, 0, null, null, 1, null, 0, false, DownloadJob.QUEUED, null, 1))
        fetcher.ok(Ao3Urls.navigate(work), navigateHtml(101, 102))
        fetcher.ok(Ao3Urls.chapter(work, 101), CHAPTER_HTML)
        fetcher.ok(Ao3Urls.chapter(work, 102), CHAPTER_HTML)
        assertEquals(listOf(101L, 102L), (source.chapters(work) as Loaded.Ok).value.map { it.chapterId })
        val failed = FetchResult.Failed(null)
        fetcher.responses[Ao3Urls.chapter(work, 103).toString()] = failed
        assertTrue(source.chapter(work, ChapterRef(101, 1, "")) is Loaded.Ok)
        // Errors pass through unchanged.
        assertEquals(Loaded.Error(failed), source.chapter(work, ChapterRef(103, 3, "")))
        source.prefetch(work, ChapterRef(102, 2, ""))
        scope.advanceUntilIdle()
        assertEquals(
            listOf(Ao3Urls.navigate(work), Ao3Urls.chapter(work, 101), Ao3Urls.chapter(work, 103), Ao3Urls.chapter(work, 102))
                .map { it.toString() },
            fetcher.calls,
        )
        assertNull(withTimeoutOrNull(10_000) { source.listUpdates(work).first() })
    }
}

/** In-memory [DownloadDao]: Room's own threads don't mix with virtual time. */
internal class FakeDownloadDao : DownloadDao {
    private val rows = MutableStateFlow<Map<Long, DownloadEntity>>(emptyMap())
    private val chapterRows = mutableMapOf<Long, List<DownloadChapterEntity>>()

    override fun observeAll(): Flow<List<DownloadEntity>> = rows.map { it.values.toList() }
    override fun observe(workId: Long): Flow<DownloadEntity?> = rows.map { it[workId] }
    override suspend fun get(workId: Long) = rows.value[workId]
    override suspend fun chapters(workId: Long) = chapterRows[workId].orEmpty().sortedBy { it.index }
    override suspend fun upsert(e: DownloadEntity) { rows.value = rows.value + (e.workId to e) }
    override suspend fun insertChapters(chapters: List<DownloadChapterEntity>) {
        chapters.groupBy { it.workId }.forEach { (id, l) -> chapterRows[id] = chapterRows[id].orEmpty() + l }
    }
    override suspend fun setJob(workId: Long, job: DownloadJob, failure: String?) {
        rows.value[workId]?.let { upsert(it.copy(job = job, failure = failure)) }
    }
    override suspend fun requeueIfDownloading(workId: Long) {
        rows.value[workId]?.takeIf { it.job == DownloadJob.DOWNLOADING }?.let { upsert(it.copy(job = DownloadJob.QUEUED, failure = null)) }
    }
    override suspend fun failDownloading(failure: String) {
        rows.value.values.filter { it.job == DownloadJob.DOWNLOADING }.forEach { upsert(it.copy(job = DownloadJob.FAILED, failure = failure)) }
    }
    override suspend fun setPostedRemote(workId: Long, posted: Int) {
        rows.value[workId]?.let { upsert(it.copy(chaptersPostedRemote = posted)) }
    }
    override suspend fun deleteChapters(workId: Long) { chapterRows.remove(workId) }
    override suspend fun deleteRow(workId: Long) { rows.value = rows.value - workId }
    override suspend fun nextQueued() = rows.value.values.filter { it.job == DownloadJob.QUEUED }.minByOrNull { it.requestedAt }
    override fun observeCopyIds(): Flow<List<Long>> = rows.map { m -> m.values.filter { it.hasCopy }.map { it.workId } }
}
