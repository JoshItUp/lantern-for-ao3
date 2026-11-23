package io.github.joshitup.lantern.data.download

import io.github.joshitup.lantern.data.db.DownloadDao
import io.github.joshitup.lantern.data.db.DownloadJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Preference order lives in [sources]: listOf(fullWorkPage). EPUB is prepended when it exists. */
class Downloader(
    private val sources: List<DownloadSource>,
    private val store: DownloadStore,
    private val dao: DownloadDao,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Runs one download/update for [workId] to completion; returns the failure, or null on success. */
    suspend fun run(workId: Long, interactive: Boolean): DownloadFailure? {
        var writer: DownloadWriter? = null
        try {
            dao.setJob(workId, DownloadJob.DOWNLOADING, null)
            val w = withContext(io) { store.begin(workId) }.also { writer = it }
            val sink = ChapterSink { index, content -> w.write(index, content) }
            for (source in sources) {
                when (val r = source.fetch(workId, interactive, sink)) {
                    SourceResult.Unavailable -> continue
                    is SourceResult.Ok -> {
                        w.commit(r.blurb, source.kind, r.chapters, r.postedRemote)
                        return null
                    }
                    is SourceResult.Failed -> return fail(workId, w, r.reason)
                }
            }
            return fail(workId, w, DownloadFailure.NETWORK)
        } catch (e: CancellationException) {
            withContext(NonCancellable + io) { writer?.abort() }
            throw e
        } catch (e: Throwable) {
            // Errors too (e.g. OutOfMemoryError on a huge page, a Room or IO error): never leave the row DOWNLOADING.
            return fail(workId, writer, DownloadFailure.NETWORK)
        }
    }

    private suspend fun fail(workId: Long, writer: DownloadWriter?, reason: DownloadFailure): DownloadFailure {
        withContext(NonCancellable + io) {
            writer?.abort()
            dao.setJob(workId, DownloadJob.FAILED, reason.message)
        }
        return reason
    }
}
