package app.ao3reader.data.download

import app.ao3reader.ao3.model.ChapterContent
import app.ao3reader.ao3.model.ChapterRef
import app.ao3reader.ao3.model.WorkBlurb
import app.ao3reader.data.db.DownloadChapterEntity
import app.ao3reader.data.db.DownloadDao
import app.ao3reader.data.db.DownloadEntity
import app.ao3reader.data.db.DownloadJob
import app.ao3reader.data.db.SourceKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

const val STORED_FORMAT_VERSION = 1

private const val COUNT_MARKER = ".count"
private fun markerValue(row: DownloadEntity) = "${row.chaptersStored},${row.downloadedAt}"

/**
 * Downloaded works on disk: `{root}/{workId}/{index}.json`, one file per chapter, plus rows in Room.
 * A new copy is written to `{workId}.tmp/` and swapped in, so a failed update never harms the old copy.
 */
class DownloadStore(
    private val root: File,
    private val dao: DownloadDao,
    private val json: Json,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private fun dir(workId: Long) = File(root, workId.toString())
    private fun tmp(workId: Long) = File(root, "$workId.tmp")
    private fun old(workId: Long) = File(root, "$workId.old")

    /** Begins a fresh copy in `{workId}.tmp/`, deleting any earlier leftover. */
    fun begin(workId: Long): DownloadWriter {
        val t = tmp(workId)
        t.deleteRecursively()
        t.mkdirs()
        return DownloadWriter(workId, t, dir(workId), old(workId), dao, json, io, clock)
    }

    suspend fun readChapter(workId: Long, index: Int): ChapterContent? = withContext(io) {
        try {
            val f = File(dir(workId), "$index.json")
            if (f.isFile) json.decodeFromString(ChapterContent.serializer(), f.readText()) else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    suspend fun delete(workId: Long) {
        withContext(io) {
            dir(workId).deleteRecursively(); tmp(workId).deleteRecursively(); old(workId).deleteRecursively()
        }
        dao.delete(workId)
    }

    /**
     * Removes leftover `*.tmp` and `*.old` dirs; call once at app start. A crash mid-swap is resolved from the
     * `.count` marker (chapter count, downloadedAt) that commit leaves in the new dir until the rows are written.
     */
    suspend fun cleanUp() {
        val names = withContext(io) { root.listFiles()?.map { it.name } ?: emptyList() }
        withContext(io) { names.filter { it.endsWith(".tmp") }.forEach { File(root, it).deleteRecursively() } }
        val ids = names.mapNotNull { it.removeSuffix(".old").toLongOrNull() }.toSet()
        for (id in ids) {
            val row = dao.get(id)
            withContext(io) {
                val live = dir(id); val o = old(id)
                val marker = File(live, COUNT_MARKER)
                // The marker survives only if the rows were never written for this copy.
                val unCommitted = marker.isFile && (row == null || !row.hasCopy || marker.readText().trim() != markerValue(row))
                when {
                    unCommitted -> {
                        live.deleteRecursively()
                        if (o.exists()) o.renameTo(live)
                    }
                    live.exists() -> { o.deleteRecursively(); marker.delete() }
                    o.exists() -> o.renameTo(live)
                }
            }
        }
    }

    fun observeIds(): Flow<Set<Long>> = dao.observeCopyIds().map { it.toSet() }
}

class DownloadWriter internal constructor(
    private val workId: Long,
    private val tmpDir: File,
    private val liveDir: File,
    private val oldDir: File,
    private val dao: DownloadDao,
    private val json: Json,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
) {
    fun write(index: Int, content: ChapterContent) {
        File(tmpDir, "$index.json").writeText(json.encodeToString(ChapterContent.serializer(), content))
    }

    /** Atomically replaces `{workId}/` with the tmp dir and the rows in one transaction; hasCopy = true, job = NONE. */
    suspend fun commit(blurb: WorkBlurb?, source: SourceKind, chapters: List<ChapterRef>, postedRemote: Int?) {
        val size = withContext(io) { tmpDir.listFiles()?.sumOf { it.length() } ?: 0L }
        val existing = dao.get(workId)
        val now = clock()
        val entity = DownloadEntity(
            workId = workId,
            title = blurb?.title ?: existing?.title ?: "",
            authors = blurb?.authors?.joinToString(", ") ?: existing?.authors ?: "",
            blurbJson = blurb?.let { json.encodeToString(WorkBlurb.serializer(), it) } ?: existing?.blurbJson,
            chaptersStored = chapters.size,
            chaptersPostedRemote = postedRemote,
            source = source,
            formatVersion = STORED_FORMAT_VERSION,
            downloadedAt = now,
            sizeBytes = size,
            hasCopy = true,
            job = DownloadJob.NONE,
            failure = null,
            requestedAt = existing?.requestedAt ?: now,
        )
        val rows = chapters.mapIndexed { i, c -> DownloadChapterEntity(workId, i, c.chapterId, c.number, c.title) }
        // Once started the swap always finishes: WorkManager may cancel the worker mid-commit.
        withContext(NonCancellable + io) {
            // A lone .old is the only good copy left by an earlier crash.
            if (oldDir.exists() && !liveDir.exists()) oldDir.renameTo(liveDir)
            File(tmpDir, COUNT_MARKER).writeText(markerValue(entity))
            oldDir.deleteRecursively()
            if (liveDir.exists() && !liveDir.renameTo(oldDir)) error("Could not move the old copy aside")
            if (!tmpDir.renameTo(liveDir)) {
                if (oldDir.exists()) oldDir.renameTo(liveDir)
                error("Could not move the new copy into place")
            }
            try {
                dao.replaceCopy(entity, rows)
            } catch (e: Throwable) {
                // Rows are unchanged, so put the old files back to keep rows and files consistent.
                liveDir.deleteRecursively()
                if (oldDir.exists()) oldDir.renameTo(liveDir)
                throw e
            }
            File(liveDir, COUNT_MARKER).delete()
            oldDir.deleteRecursively()
        }
    }

    fun abort() {
        tmpDir.deleteRecursively()
    }
}
