package io.github.joshitup.lantern.data.download

import androidx.room.Room
import io.github.joshitup.lantern.data.db.AppDatabase
import io.github.joshitup.lantern.data.db.DownloadEntity
import io.github.joshitup.lantern.data.db.DownloadJob
import io.github.joshitup.lantern.data.db.SourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DownloadRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var root: File
    private lateinit var store: DownloadStore
    private var enqueued = 0
    private var now = 5000L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        root = File(RuntimeEnvironment.getApplication().cacheDir, "repo-test-${System.nanoTime()}").also { it.mkdirs() }
        store = DownloadStore(root, db.downloadDao(), Json, Dispatchers.Unconfined) { now }
    }

    @After fun tearDown() { db.close(); root.deleteRecursively() }

    private fun repo() = DownloadRepository(db.downloadDao(), store, { enqueued++ }, { now })

    private suspend fun readyRepo() = repo().also { it.startup() }

    private fun row(id: Long, job: DownloadJob, hasCopy: Boolean = false, failure: String? = null) = DownloadEntity(
        workId = id, title = "Old", authors = "x", blurbJson = if (hasCopy) "{}" else null,
        chaptersStored = if (hasCopy) 3 else 0, chaptersPostedRemote = if (hasCopy) 3 else null,
        source = if (hasCopy) SourceKind.FULL_WORK_PAGE else null, formatVersion = 1,
        downloadedAt = if (hasCopy) 100L else null, sizeBytes = if (hasCopy) 42L else 0L,
        hasCopy = hasCopy, job = job, failure = failure, requestedAt = 1L,
    )

    @Test fun request_newWork_createsQueuedRowAndEnqueues() = runTest {
        readyRepo().request(7L, "Title", listOf("a", "b"))
        val r = db.downloadDao().get(7L)!!
        assertEquals(DownloadJob.QUEUED, r.job)
        assertEquals("Title", r.title)
        assertEquals("a, b", r.authors)
        assertEquals(now, r.requestedAt)
        assertFalse(r.hasCopy)
        assertNull(r.failure)
        assertEquals(1, enqueued)
    }

    @Test fun request_whileQueued_noop() = runTest {
        val repo = readyRepo()
        db.downloadDao().upsert(row(7L, DownloadJob.QUEUED))
        repo.request(7L, "New", listOf("n"))
        db.downloadDao().upsert(row(8L, DownloadJob.DOWNLOADING))
        repo.request(8L, "New", listOf("n"))
        assertEquals(row(7L, DownloadJob.QUEUED), db.downloadDao().get(7L))
        assertEquals(row(8L, DownloadJob.DOWNLOADING), db.downloadDao().get(8L))
        assertEquals(0, enqueued)
    }

    @Test fun request_existingCopy_keepsHasCopy() = runTest {
        val repo = readyRepo()
        db.downloadDao().upsert(row(7L, DownloadJob.FAILED, hasCopy = true, failure = "boom"))
        repo.request(7L, "Title", listOf("a"))
        val r = db.downloadDao().get(7L)!!
        assertEquals(DownloadJob.QUEUED, r.job)
        assertNull(r.failure)
        assertTrue(r.hasCopy)
        assertEquals(3, r.chaptersStored)
        assertEquals(100L, r.downloadedAt)
        assertEquals(42L, r.sizeBytes)
        assertEquals("{}", r.blurbJson)
        assertEquals(now, r.requestedAt)
        assertEquals(1, enqueued)
    }

    @Test fun notePostedRemote_ignoredWithoutCopy() = runTest {
        val repo = readyRepo()
        repo.notePostedRemote(9L, 5) // no row at all
        assertNull(db.downloadDao().get(9L))
        db.downloadDao().upsert(row(7L, DownloadJob.QUEUED))
        repo.notePostedRemote(7L, 5)
        assertNull(db.downloadDao().get(7L)!!.chaptersPostedRemote)
        db.downloadDao().upsert(row(8L, DownloadJob.NONE, hasCopy = true))
        repo.notePostedRemote(8L, 5)
        assertEquals(5, db.downloadDao().get(8L)!!.chaptersPostedRemote)
    }

    @Test fun staleDownloadingResetToFailed() = runTest {
        db.downloadDao().upsert(row(7L, DownloadJob.DOWNLOADING, hasCopy = true))
        db.downloadDao().upsert(row(8L, DownloadJob.QUEUED))
        File(root, "7.tmp").mkdirs()
        val repo = repo()
        assertFalse(repo.ready.isCompleted)
        repo.startup()
        assertTrue(repo.ready.isCompleted)
        val r = db.downloadDao().get(7L)!!
        assertEquals(DownloadJob.FAILED, r.job)
        assertEquals(DownloadFailure.NETWORK.message, r.failure)
        assertTrue(r.hasCopy)
        assertEquals(DownloadJob.QUEUED, db.downloadDao().get(8L)!!.job)
        assertFalse(File(root, "7.tmp").exists())
    }

    @Test fun delete_removesRowAndFiles() = runTest {
        val repo = readyRepo()
        db.downloadDao().upsert(row(7L, DownloadJob.NONE, hasCopy = true))
        File(root, "7").mkdirs()
        repo.delete(7L)
        assertNull(db.downloadDao().get(7L))
        assertFalse(File(root, "7").exists())
    }

    @Test fun startup_withQueuedRows_enqueues() = runTest {
        db.downloadDao().upsert(row(8L, DownloadJob.QUEUED))
        repo().startup()
        assertEquals(1, enqueued)
        assertEquals(DownloadJob.QUEUED, db.downloadDao().get(8L)!!.job)
    }

    @Test fun startup_noQueuedRows_noEnqueue() = runTest {
        db.downloadDao().upsert(row(7L, DownloadJob.DOWNLOADING))
        db.downloadDao().upsert(row(9L, DownloadJob.NONE, hasCopy = true))
        repo().startup()
        assertEquals(0, enqueued)
    }
}
