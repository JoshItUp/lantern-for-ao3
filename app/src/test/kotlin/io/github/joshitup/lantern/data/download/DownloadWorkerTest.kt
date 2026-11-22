package io.github.joshitup.lantern.data.download

import android.content.Context
import androidx.room.Room
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import io.github.joshitup.lantern.data.db.AppDatabase
import io.github.joshitup.lantern.data.db.DownloadEntity
import io.github.joshitup.lantern.data.db.DownloadJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DownloadWorkerTest {
    private lateinit var db: AppDatabase
    private lateinit var root: File
    private lateinit var repo: DownloadRepository
    private val calls = mutableListOf<Pair<Long, Boolean>>()
    private var foreground = false

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        root = File(RuntimeEnvironment.getApplication().cacheDir, "worker-test-${System.nanoTime()}").also { it.mkdirs() }
        val store = DownloadStore(root, db.downloadDao(), Json, Dispatchers.Unconfined) { 1L }
        repo = DownloadRepository(db.downloadDao(), store, {}, { 1L })
    }

    @After fun tearDown() { db.close(); root.deleteRecursively() }

    private suspend fun queue(id: Long, requestedAt: Long) = db.downloadDao().upsert(
        DownloadEntity(id, "W$id", "a", null, 0, null, null, 0, null, 0, false, DownloadJob.QUEUED, null, requestedAt),
    )

    /** Records each call and finishes the row the way [Downloader] does. */
    private fun fakeRun(fail: Set<Long> = emptySet()): suspend (Long, Boolean) -> DownloadFailure? = { id, interactive ->
        calls += id to interactive
        db.downloadDao().setJob(id, DownloadJob.DOWNLOADING, null)
        if (id in fail) {
            db.downloadDao().setJob(id, DownloadJob.FAILED, DownloadFailure.NETWORK.message)
            DownloadFailure.NETWORK
        } else {
            db.downloadDao().setJob(id, DownloadJob.NONE, null)
            null
        }
    }

    private fun worker(run: suspend (Long, Boolean) -> DownloadFailure?): DownloadWorker {
        val deps = DownloadWorker.Deps(repo, run) { foreground }
        return TestListenableWorkerBuilder<DownloadWorker>(RuntimeEnvironment.getApplication())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    DownloadWorker(appContext, workerParameters, deps)
            })
            .build()
    }

    @Test fun drainsQueueInRequestOrder() = runTest {
        repo.startup()
        queue(3L, requestedAt = 30)
        queue(1L, requestedAt = 10)
        queue(2L, requestedAt = 20)
        foreground = true
        val result = worker(fakeRun()).doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf(1L to true, 2L to true, 3L to true), calls)
    }

    @Test fun failureDoesNotStopQueue() = runTest {
        repo.startup()
        queue(1L, requestedAt = 10)
        queue(2L, requestedAt = 20)
        val result = worker(fakeRun(fail = setOf(1L))).doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf(1L to false, 2L to false), calls)
        assertEquals(DownloadJob.FAILED, db.downloadDao().get(1L)!!.job)
        assertEquals(DownloadJob.NONE, db.downloadDao().get(2L)!!.job)
    }

    @Test fun waitsForStartup() = runTest {
        queue(1L, requestedAt = 10)
        val job = launch { worker(fakeRun()).doWork() }
        testScheduler.advanceUntilIdle()
        assertEquals(emptyList<Pair<Long, Boolean>>(), calls)
        repo.startup()
        job.join()
        assertEquals(listOf(1L to false), calls)
    }

    @Test fun cancelledRun_requeued() = runTest {
        repo.startup()
        queue(1L, requestedAt = 10)
        val started = CompletableDeferred<Unit>()
        val job = launch {
            worker { id, _ ->
                db.downloadDao().setJob(id, DownloadJob.DOWNLOADING, null)
                started.complete(Unit)
                awaitCancellation()
            }.doWork()
        }
        started.await()
        job.cancelAndJoin()
        val r = db.downloadDao().get(1L)!!
        assertEquals(DownloadJob.QUEUED, r.job)
        assertNull(r.failure)
    }

    @Test fun runThrowsError_rowFailedAndQueueContinues() = runTest {
        repo.startup()
        queue(1L, requestedAt = 10)
        queue(2L, requestedAt = 20)
        val ok = fakeRun()
        val result = worker { id, interactive ->
            if (id == 1L) {
                calls += id to interactive
                db.downloadDao().setJob(id, DownloadJob.DOWNLOADING, null)
                throw OutOfMemoryError("big")
            }
            ok(id, interactive)
        }.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf(1L to false, 2L to false), calls)
        val r1 = db.downloadDao().get(1L)!!
        assertEquals(DownloadJob.FAILED, r1.job)
        assertEquals(DownloadFailure.NETWORK.message, r1.failure)
        assertEquals(DownloadJob.NONE, db.downloadDao().get(2L)!!.job)
    }

    @Test fun cancelAfterCommit_notRequeued() = runTest {
        repo.startup()
        queue(1L, requestedAt = 10)
        val committed = CompletableDeferred<Unit>()
        val job = launch {
            worker { id, _ ->
                db.downloadDao().setJob(id, DownloadJob.DOWNLOADING, null)
                db.downloadDao().setJob(id, DownloadJob.NONE, null) // committed
                committed.complete(Unit)
                awaitCancellation()
            }.doWork()
        }
        committed.await()
        job.cancelAndJoin()
        assertEquals(DownloadJob.NONE, db.downloadDao().get(1L)!!.job)
    }
}
