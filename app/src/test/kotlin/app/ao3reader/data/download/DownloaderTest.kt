package app.ao3reader.data.download

import androidx.room.Room
import app.ao3reader.ao3.model.Block
import app.ao3reader.ao3.model.ChapterContent
import app.ao3reader.ao3.model.ChapterRef
import app.ao3reader.data.db.AppDatabase
import app.ao3reader.data.db.DownloadEntity
import app.ao3reader.data.db.DownloadJob
import app.ao3reader.data.db.SourceKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DownloaderTest {
    private lateinit var db: AppDatabase
    private lateinit var root: File
    private lateinit var store: DownloadStore
    private val id = 7L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        root = File(RuntimeEnvironment.getApplication().cacheDir, "dler-test-${System.nanoTime()}").also { it.mkdirs() }
        store = DownloadStore(root, db.downloadDao(), Json, Dispatchers.Unconfined) { 1000L }
    }

    @After fun tearDown() { db.close(); root.deleteRecursively() }

    private fun chapter(n: Int, text: String) = ChapterContent(
        workId = id, ref = ChapterRef(100L + n, n, "Ch $n"), workTitle = "W", authors = listOf("a"),
        beginNotes = emptyList(), body = listOf(Block.Text(text, emptyList())), endNotes = emptyList(), summary = emptyList(),
    )

    private inner class FakeSource(
        override val kind: SourceKind = SourceKind.FULL_WORK_PAGE,
        val chapters: Int = 2,
        val label: String = "v1",
        val outcome: (ChapterSink) -> SourceResult? = { null },
    ) : DownloadSource {
        var calls = 0
        override suspend fun fetch(workId: Long, interactive: Boolean, sink: ChapterSink): SourceResult {
            calls++
            outcome(sink)?.let { return it }
            val refs = (1..chapters).map { ChapterRef(100L + it, it, "Ch $it") }
            refs.forEachIndexed { i, r -> sink.chapter(i, chapter(r.number, "$label ${r.number}")) }
            return SourceResult.Ok(null, refs, chapters)
        }
    }

    private fun downloader(vararg s: DownloadSource) =
        Downloader(s.toList(), store, db.downloadDao(), Dispatchers.Unconfined)

    private suspend fun queue() = db.downloadDao().upsert(
        DownloadEntity(id, "W", "a", null, 0, null, null, 0, null, 0, false, DownloadJob.QUEUED, null, 1),
    )

    private fun text(c: ChapterContent?) = (c!!.body[0] as Block.Text).text

    @Test fun success_commitsCopy() = runTest {
        queue()
        assertNull(downloader(FakeSource()).run(id, false))
        val row = db.downloadDao().get(id)!!
        assertTrue(row.hasCopy)
        assertEquals(DownloadJob.NONE, row.job)
        assertEquals(SourceKind.FULL_WORK_PAGE, row.source)
        assertEquals("v1 2", text(store.readChapter(id, 1)))
    }

    @Test fun failure_keepsExistingCopy() = runTest {
        queue()
        assertNull(downloader(FakeSource()).run(id, false))
        val failing = FakeSource(outcome = { s ->
            s.chapter(0, chapter(1, "partial"))
            SourceResult.Failed(DownloadFailure.NETWORK, null)
        })
        assertEquals(DownloadFailure.NETWORK, downloader(failing).run(id, false))
        val row = db.downloadDao().get(id)!!
        assertTrue(row.hasCopy)
        assertEquals(DownloadJob.FAILED, row.job)
        assertEquals(DownloadFailure.NETWORK.message, row.failure)
        assertEquals("v1 1", text(store.readChapter(id, 0)))
        assertEquals("v1 2", text(store.readChapter(id, 1)))
        assertFalse(File(root, "$id.tmp").exists())
    }

    @Test fun unavailable_fallsThroughToNextSource() = runTest {
        queue()
        val first = FakeSource(kind = SourceKind.EPUB, outcome = { SourceResult.Unavailable })
        val second = FakeSource()
        assertNull(downloader(first, second).run(id, false))
        assertEquals(1, first.calls)
        assertEquals(1, second.calls)
        assertEquals(SourceKind.FULL_WORK_PAGE, db.downloadDao().get(id)!!.source)
    }

    @Test fun exception_becomesNetworkFailure() = runTest {
        queue()
        val boom = FakeSource(outcome = { error("boom") })
        assertEquals(DownloadFailure.NETWORK, downloader(boom).run(id, false))
        assertEquals(DownloadJob.FAILED, db.downloadDao().get(id)!!.job)
        assertFalse(File(root, "$id.tmp").exists())
    }

    @Test fun downloaderThrowableOutsideParse_failsRow() = runTest {
        queue()
        // An Error, not an Exception (e.g. OutOfMemoryError on a huge full-work page).
        val oom = FakeSource(outcome = { s -> s.chapter(0, chapter(1, "x")); throw OutOfMemoryError("big") })
        assertEquals(DownloadFailure.NETWORK, downloader(oom).run(id, false))
        val row = db.downloadDao().get(id)!!
        assertEquals(DownloadJob.FAILED, row.job)
        assertEquals(DownloadFailure.NETWORK.message, row.failure)
        assertFalse(File(root, "$id.tmp").exists())
    }

    @Test fun blockedCopy() {
        assertEquals("AO3 asked for a check. Tap Retry.", DownloadFailure.BLOCKED.message)
    }

    @Test fun cancellation_abortsAndRethrows() = runTest {
        queue()
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val src = object : DownloadSource {
            override val kind = SourceKind.FULL_WORK_PAGE
            override suspend fun fetch(workId: Long, interactive: Boolean, sink: ChapterSink): SourceResult {
                sink.chapter(0, chapter(1, "x"))
                started.complete(Unit)
                gate.await()
                return SourceResult.Unavailable
            }
        }
        var thrown: Throwable? = null
        val job = launch {
            try { downloader(src).run(id, false) } catch (e: CancellationException) { thrown = e; throw e }
        }
        started.await()
        assertTrue(File(root, "$id.tmp").exists())
        job.cancelAndJoin()
        assertNotNull(thrown)
        assertFalse(File(root, "$id.tmp").exists())
    }
}
