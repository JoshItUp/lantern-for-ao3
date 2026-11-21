package app.ao3reader.data.download

import androidx.room.Room
import app.ao3reader.ao3.model.Block
import app.ao3reader.ao3.model.ChapterContent
import app.ao3reader.ao3.model.ChapterRef
import app.ao3reader.data.db.AppDatabase
import app.ao3reader.data.db.DownloadChapterEntity
import app.ao3reader.data.db.DownloadDao
import app.ao3reader.data.db.DownloadEntity
import app.ao3reader.data.db.DownloadJob
import app.ao3reader.data.db.SourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
class DownloadStoreTest {
    private lateinit var db: AppDatabase
    private lateinit var root: File
    private lateinit var store: DownloadStore
    private var now = 1000L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        root = File(RuntimeEnvironment.getApplication().cacheDir, "dl-test-${System.nanoTime()}").also { it.mkdirs() }
        store = DownloadStore(root, db.downloadDao(), Json, Dispatchers.Unconfined) { now }
    }

    @After fun tearDown() { db.close(); root.deleteRecursively() }

    private fun chapter(n: Int, text: String = "body $n") = ChapterContent(
        workId = 7, ref = ChapterRef(100L + n, n, "Ch $n"), workTitle = "W", authors = listOf("a"),
        beginNotes = emptyList(), body = listOf(Block.Text(text, emptyList())), endNotes = emptyList(), summary = emptyList(),
    )

    private fun refs(n: Int) = (1..n).map { ChapterRef(100L + it, it, "Ch $it") }

    private fun queuedRow(id: Long, title: String, requestedAt: Long) =
        DownloadEntity(id, title, "X, Y", null, 0, null, null, 0, null, 0, false, DownloadJob.QUEUED, null, requestedAt)

    private fun text(c: ChapterContent?) = (c!!.body[0] as Block.Text).text

    private suspend fun commitCopy(count: Int, label: String = "v1") {
        val w = store.begin(7)
        (1..count).forEach { w.write(it - 1, chapter(it, "$label $it")) }
        w.commit(null, SourceKind.FULL_WORK_PAGE, refs(count), 5)
    }

    @Test fun commit_thenReadEachChapter() = runTest {
        commitCopy(3)
        (0..2).forEach { assertEquals("v1 ${it + 1}", text(store.readChapter(7, it))) }
        assertNull(store.readChapter(7, 3))
        val row = db.downloadDao().get(7)!!
        assertEquals(3, row.chaptersStored); assertEquals(5, row.chaptersPostedRemote)
        assertEquals(1000L, row.downloadedAt); assertEquals(STORED_FORMAT_VERSION, row.formatVersion)
        assertTrue(row.hasCopy); assertEquals(DownloadJob.NONE, row.job)
        assertEquals(File(root, "7").listFiles()!!.sumOf { it.length() }, row.sizeBytes)
        assertEquals(listOf(0, 1, 2), db.downloadDao().chapters(7).map { it.index })
        assertFalse(File(root, "7.tmp").exists()); assertFalse(File(root, "7.old").exists())
    }

    @Test fun commit_keepsRequestedAtAndExistingTitle() = runTest {
        db.downloadDao().upsert(queuedRow(7, "Old T", 42))
        commitCopy(1)
        val row = db.downloadDao().get(7)!!
        assertEquals(42L, row.requestedAt); assertEquals("Old T", row.title); assertEquals("X, Y", row.authors)
    }

    @Test fun failedUpdate_keepsOldCopy() = runTest {
        commitCopy(2)
        val w = store.begin(7)
        w.write(0, chapter(1, "partial"))
        w.abort()
        assertEquals("v1 1", text(store.readChapter(7, 0)))
        assertNotNull(store.readChapter(7, 1))
        assertTrue(db.downloadDao().get(7)!!.hasCopy)
        assertFalse(File(root, "7.tmp").exists())
    }

    @Test fun update_replacesCopy() = runTest {
        commitCopy(3)
        commitCopy(2, "v2")
        assertEquals("v2 1", text(store.readChapter(7, 0)))
        assertNull(store.readChapter(7, 2))
        assertEquals(2, db.downloadDao().chapters(7).size)
        assertFalse(File(root, "7.old").exists())
    }

    @Test fun crashBetweenRenames_cleanUpRestoresOld() = runTest {
        commitCopy(1)
        assertTrue(File(root, "7").renameTo(File(root, "7.old")))
        File(root, "7.tmp").mkdirs()
        store.cleanUp()
        assertNotNull(store.readChapter(7, 0))
        assertFalse(File(root, "7.old").exists()); assertFalse(File(root, "7.tmp").exists())
    }

    @Test fun cleanUp_deletesOldWhenLiveExists() = runTest {
        commitCopy(1)
        File(root, "7.old").mkdirs()
        store.cleanUp()
        assertFalse(File(root, "7.old").exists()); assertNotNull(store.readChapter(7, 0))
    }

    @Test fun cleanUp_removesTmp() = runTest {
        File(root, "9.tmp").mkdirs(); File(root, "9.tmp/0.json").writeText("x")
        store.cleanUp()
        assertFalse(File(root, "9.tmp").exists())
    }

    @Test fun delete_removesFilesAndRows() = runTest {
        commitCopy(2)
        store.delete(7)
        assertFalse(File(root, "7").exists())
        assertNull(db.downloadDao().get(7)); assertTrue(db.downloadDao().chapters(7).isEmpty())
        assertNull(store.readChapter(7, 0))
    }

    @Test fun readChapter_corruptFile_returnsNull() = runTest {
        commitCopy(1)
        File(root, "7/0.json").writeText("{not json")
        assertNull(store.readChapter(7, 0))
    }

    @Test fun observeIds_onlyHasCopy() = runTest {
        commitCopy(1)
        db.downloadDao().upsert(queuedRow(8, "Q", 1))
        assertEquals(setOf(7L), store.observeIds().first())
    }

    private fun failingStore(): DownloadStore {
        val real = db.downloadDao()
        val dao = object : DownloadDao by real {
            override suspend fun replaceCopy(e: DownloadEntity, chapters: List<DownloadChapterEntity>) = error("boom")
        }
        return DownloadStore(root, dao, Json, Dispatchers.Unconfined) { now }
    }

    private suspend fun failingCommit(s: DownloadStore, label: String) {
        val w = s.begin(7)
        w.write(0, chapter(1, "$label 1"))
        try {
            w.commit(null, SourceKind.FULL_WORK_PAGE, refs(1), 5)
            org.junit.Assert.fail("expected failure")
        } catch (e: IllegalStateException) { assertEquals("boom", e.message) }
    }

    @Test fun replaceCopyThrows_oldCopyStillReads() = runTest {
        commitCopy(2)
        failingCommit(failingStore(), "v2")
        assertEquals("v1 1", text(store.readChapter(7, 0)))
        assertNotNull(store.readChapter(7, 1))
        assertFalse(File(root, "7.old").exists())
        assertEquals(2, db.downloadDao().get(7)!!.chaptersStored)
    }

    @Test fun crashAfterSwapBeforeDb_cleanUpRestoresOld() = runTest {
        commitCopy(2)
        assertTrue(File(root, "7").renameTo(File(root, "7.old")))
        File(root, "7").mkdirs()
        File(root, "7/0.json").writeText("new files")
        File(root, "7/.count").writeText("3,5000")
        store.cleanUp()
        assertEquals("v1 1", text(store.readChapter(7, 0)))
        assertNotNull(store.readChapter(7, 1))
        assertFalse(File(root, "7.old").exists())
    }

    @Test fun crashAfterDb_cleanUpKeepsNewAndDropsMarker() = runTest {
        commitCopy(2)
        val row = db.downloadDao().get(7)!!
        assertTrue(File(root, "7").renameTo(File(root, "7.old")))
        File(root, "7").mkdirs()
        File(root, "7/0.json").writeText(Json.encodeToString(ChapterContent.serializer(), chapter(1, "v2 1")))
        File(root, "7/.count").writeText("${row.chaptersStored},${row.downloadedAt}")
        store.cleanUp()
        assertEquals("v2 1", text(store.readChapter(7, 0)))
        assertFalse(File(root, "7.old").exists()); assertFalse(File(root, "7/.count").exists())
    }

    @Test fun loneNewCopyWithoutRow_cleanUpDeletesIt() = runTest {
        File(root, "7").mkdirs()
        File(root, "7/0.json").writeText("x"); File(root, "7/.count").writeText("1,1000")
        store.cleanUp()
        assertFalse(File(root, "7").exists())
    }

    @Test fun commit_leavesNoMarkerAndSizeExcludesIt() = runTest {
        commitCopy(1)
        assertFalse(File(root, "7/.count").exists())
        assertEquals(File(root, "7/0.json").length(), db.downloadDao().get(7)!!.sizeBytes)
    }

    @Test fun loneOld_atCommit_restoredNotDeleted() = runTest {
        commitCopy(2)
        assertTrue(File(root, "7").renameTo(File(root, "7.old")))
        failingCommit(failingStore(), "v2")
        assertEquals("v1 1", text(store.readChapter(7, 0)))
        assertNotNull(store.readChapter(7, 1))
    }
}
