package io.github.joshitup.lantern.data.download

import io.github.joshitup.lantern.data.db.DownloadDao
import io.github.joshitup.lantern.data.db.DownloadEntity
import io.github.joshitup.lantern.data.db.DownloadJob
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow

/** The download queue: rows in Room, files in [store], and a kick to the background worker via [enqueue]. */
class DownloadRepository(
    private val dao: DownloadDao,
    private val store: DownloadStore,
    private val enqueue: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val readyJob: CompletableJob = Job()

    /** Completes once [startup] has run. Nothing touches the store or the queue before then. */
    val ready: Job get() = readyJob

    /**
     * Call once per process before anything else: clears leftover temp dirs, and fails rows a dead process
     * left mid-download (no automatic retry). Rows still QUEUED (requested, never started) get a worker run, so
     * they never wait for an unrelated request. [DownloadStore.cleanUp] must not run alongside a commit.
     */
    suspend fun startup() {
        try {
            store.cleanUp()
            dao.failDownloading(DownloadFailure.NETWORK.message)
            if (dao.nextQueued() != null) enqueue()
        } finally {
            readyJob.complete()
        }
    }

    fun observe(workId: Long): Flow<DownloadEntity?> = dao.observe(workId)

    fun observeAll(): Flow<List<DownloadEntity>> = dao.observeAll()

    fun downloadedIds(): Flow<Set<Long>> = store.observeIds()

    /** Download, update or retry: sets job = QUEUED (keeps any copy) and kicks the worker. A no-op if already QUEUED/DOWNLOADING. */
    suspend fun request(workId: Long, title: String, authors: List<String>) {
        ready.join()
        val existing = dao.get(workId)
        if (existing?.job == DownloadJob.QUEUED || existing?.job == DownloadJob.DOWNLOADING) return
        val now = clock()
        val joined = authors.joinToString(", ")
        dao.upsert(
            existing?.copy(title = title, authors = joined, job = DownloadJob.QUEUED, failure = null, requestedAt = now)
                ?: DownloadEntity(
                    workId = workId, title = title, authors = joined, blurbJson = null, chaptersStored = 0,
                    chaptersPostedRemote = null, source = null, formatVersion = STORED_FORMAT_VERSION,
                    downloadedAt = null, sizeBytes = 0, hasCopy = false, job = DownloadJob.QUEUED, failure = null,
                    requestedAt = now,
                ),
        )
        enqueue()
    }

    suspend fun delete(workId: Long) {
        ready.join()
        store.delete(workId)
    }

    /** Records AO3's current chapter count for a work with a stored copy (drives "N new chapters"). */
    suspend fun notePostedRemote(workId: Long, posted: Int) {
        if (dao.get(workId)?.hasCopy == true) dao.setPostedRemote(workId, posted)
    }

    /** The oldest queued row, for the worker. */
    internal suspend fun nextQueued(): DownloadEntity? = dao.nextQueued()

    /** Puts a row the worker was stopped in the middle of back in the queue (only if it is still DOWNLOADING). */
    internal suspend fun requeue(workId: Long) = dao.requeueIfDownloading(workId)

    /** Fails a row whose run threw instead of finishing it. */
    internal suspend fun markFailed(workId: Long) = dao.setJob(workId, DownloadJob.FAILED, DownloadFailure.NETWORK.message)
}
