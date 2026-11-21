package app.ao3reader.data.download

import app.ao3reader.data.db.DownloadDao
import app.ao3reader.data.db.DownloadJob
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
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Runs one download/update for [workId] to completion; returns the failure, or null on success. */
    suspend fun run(workId: Long, interactive: Boolean): DownloadFailure? {
        dao.setJob(workId, DownloadJob.DOWNLOADING, null)
        val writer = withContext(io) { store.begin(workId) }
        val sink = ChapterSink { index, content -> writer.write(index, content) }
        try {
            for (source in sources) {
                when (val r = source.fetch(workId, interactive, sink)) {
                    SourceResult.Unavailable -> continue
                    is SourceResult.Ok -> {
                        writer.commit(r.blurb, source.kind, r.chapters, r.postedRemote)
                        return null
                    }
                    is SourceResult.Failed -> return fail(workId, writer, r.reason)
                }
            }
            return fail(workId, writer, DownloadFailure.NETWORK)
        } catch (e: CancellationException) {
            withContext(NonCancellable + io) { writer.abort() }
            throw e
        } catch (e: Exception) {
            return fail(workId, writer, DownloadFailure.NETWORK)
        }
    }

    private suspend fun fail(workId: Long, writer: DownloadWriter, reason: DownloadFailure): DownloadFailure {
        withContext(NonCancellable + io) {
            writer.abort()
            dao.setJob(workId, DownloadJob.FAILED, reason.message)
        }
        return reason
    }
}
