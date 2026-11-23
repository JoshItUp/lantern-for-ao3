package io.github.joshitup.lantern.data.download

import io.github.joshitup.lantern.ao3.model.ChapterContent
import io.github.joshitup.lantern.ao3.model.ChapterRef
import io.github.joshitup.lantern.ao3.net.FetchResult
import io.github.joshitup.lantern.data.ChapterSource
import io.github.joshitup.lantern.data.Loaded
import io.github.joshitup.lantern.data.NotAChapterPageException
import io.github.joshitup.lantern.data.WorkRepository
import io.github.joshitup.lantern.data.matches
import io.github.joshitup.lantern.data.db.DownloadChapterEntity
import io.github.joshitup.lantern.data.db.DownloadDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** A chapter the download doesn't have, opened while AO3 can't be reached. */
class NotDownloadedException : IOException("not downloaded")

/**
 * The reader's [ChapterSource]: a work with a stored copy reads its stored chapters from [store] (no network,
 * only the current chapter in memory) and the rest online through [online]. Works without a copy go to
 * [online] unchanged. Spec §5.4.
 */
class DownloadAwareChapterSource(
    private val online: WorkRepository,
    private val dao: DownloadDao,
    private val store: DownloadStore,
    private val downloads: DownloadRepository,
    private val scope: CoroutineScope,
    private val isActing: () -> Boolean,
) : ChapterSource {
    /** Per work: the result of the latest online list check (merged, or the stored list); null while it runs. */
    private val merged = ConcurrentHashMap<Long, MutableStateFlow<List<ChapterRef>?>>()

    private fun mergedFlow(workId: Long) = merged.getOrPut(workId) { MutableStateFlow(null) }

    /** The stored chapter rows, or null when the work has no stored copy. */
    private suspend fun storedRows(workId: Long): List<DownloadChapterEntity>? {
        // Never before start-up clean-up has settled an interrupted swap.
        downloads.ready.join()
        if (dao.get(workId)?.hasCopy != true) return null
        return dao.chapters(workId).takeIf { it.isNotEmpty() }
    }

    override fun listUpdates(workId: Long): Flow<List<ChapterRef>> = mergedFlow(workId).filterNotNull()

    /**
     * The stored list at once; the online list is fetched in the background, and [listUpdates] then emits once:
     * the merged list, or the stored list when the check failed or found nothing new.
     */
    override suspend fun chapters(workId: Long): Loaded<List<ChapterRef>> {
        val rows = storedRows(workId) ?: return online.chapters(workId)
        val stored = rows.map { ChapterRef(it.chapterId, it.number, it.title) }
        val flow = mergedFlow(workId)
        // A list from an earlier open must not answer this one.
        flow.value = null
        scope.launch {
            var result = stored
            try {
                val list = (online.chapters(workId) as? Loaded.Ok)?.value?.takeIf { it.isNotEmpty() }
                if (list != null) {
                    downloads.notePostedRemote(workId, list.size)
                    // Null-tolerant: a one-shot is stored without a chapter id but listed online with one.
                    val added = list.filter { o -> stored.none { it.matches(o.chapterId, o.number) } }
                    if (added.isNotEmpty()) result = stored + added
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            flow.value = result
        }
        return Loaded.Ok(stored)
    }

    override suspend fun chapter(workId: Long, ref: ChapterRef): Loaded<ChapterContent> {
        val rows = storedRows(workId) ?: return online.chapter(workId, ref)
        val row = rows.find(ref)
        if (row != null) {
            val content = store.readChapter(workId, row.index)
            if (content != null) {
                if (isActing()) online.visitInBackground(workId, ChapterRef(row.chapterId, row.number, row.title))
                return Loaded.Ok(content)
            }
            // A stored chapter that can't be read: fall through to an online read.
        }
        return when (val r = online.chapter(workId, ref)) {
            is Loaded.Ok -> r
            is Loaded.Error -> when (val f = r.result) {
                // A signed-out restricted work keeps its own message; only network failures are "not downloaded".
                is FetchResult.Failed -> if (f.cause is NotAChapterPageException) r else notDownloaded()
                FetchResult.Blocked, is FetchResult.RateLimited -> notDownloaded()
                else -> r
            }
        }
    }

    override fun prefetch(workId: Long, ref: ChapterRef) {
        scope.launch {
            try {
                val rows = storedRows(workId)
                if (rows != null && rows.find(ref) != null) return@launch
                online.prefetch(workId, ref)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private fun notDownloaded() = Loaded.Error(FetchResult.Failed(NotDownloadedException()))

    /** By chapter id, else (an id unknown on either side) by number. */
    private fun List<DownloadChapterEntity>.find(ref: ChapterRef): DownloadChapterEntity? =
        if (ref.chapterId != null) firstOrNull { it.chapterId == ref.chapterId } ?: firstOrNull { it.chapterId == null && it.number == ref.number }
        else firstOrNull { it.number == ref.number }
}
