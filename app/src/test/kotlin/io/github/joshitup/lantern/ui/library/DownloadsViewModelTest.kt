package io.github.joshitup.lantern.ui.library

import io.github.joshitup.lantern.ao3.model.Block
import io.github.joshitup.lantern.ao3.model.WorkBlurb
import io.github.joshitup.lantern.data.db.DownloadEntity
import io.github.joshitup.lantern.data.db.DownloadJob
import io.github.joshitup.lantern.data.db.SourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var source: FakeDownloadsSource

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        source = FakeDownloadsSource()
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm() = DownloadsViewModel(source, json, dispatcher)

    private fun test(body: suspend TestScope.() -> Unit) = runTest(dispatcher) { body() }

    @Test fun listsNewestFirst() = test {
        source.rows.value = listOf(
            entity(1, downloadedAt = 100),
            entity(2, downloadedAt = 300),
            entity(3, downloadedAt = null, hasCopy = false, requestedAt = 50, job = DownloadJob.QUEUED),
            entity(4, downloadedAt = 200),
        )
        val vm = vm()
        advanceUntilIdle()
        assertEquals(listOf(2L, 4L, 1L, 3L), vm.state.value.rows.map { it.entity.workId })
        assertTrue(vm.state.value.loaded)
    }

    @Test fun deleteRequiresConfirm() = test {
        source.rows.value = listOf(entity(1, downloadedAt = 100), entity(2, downloadedAt = 50))
        val vm = vm()
        advanceUntilIdle()
        vm.askDelete(1); advanceUntilIdle()
        assertEquals(1L, vm.state.value.confirmingDelete)
        assertTrue(source.deleted.isEmpty())
        vm.cancelDelete(); advanceUntilIdle()
        assertNull(vm.state.value.confirmingDelete)
        assertTrue(source.deleted.isEmpty())
        vm.askDelete(2); vm.confirmDelete(); advanceUntilIdle()
        assertEquals(listOf(2L), source.deleted)
        assertNull(vm.state.value.confirmingDelete)
        // No confirmation pending: nothing is deleted.
        vm.confirmDelete(); advanceUntilIdle()
        assertEquals(listOf(2L), source.deleted)
    }

    @Test fun `delete and update are refused while a job runs`() = test {
        source.rows.value = listOf(
            entity(1, downloadedAt = 100, job = DownloadJob.DOWNLOADING),
            entity(2, downloadedAt = 50, job = DownloadJob.QUEUED),
        )
        val vm = vm()
        advanceUntilIdle()
        vm.askDelete(1); vm.confirmDelete()
        vm.askDelete(2); vm.confirmDelete()
        vm.update(1); vm.update(2)
        advanceUntilIdle()
        assertTrue(source.deleted.isEmpty())
        assertTrue(source.requested.isEmpty())
        assertNull(vm.state.value.confirmingDelete)
    }

    @Test fun `update requests the work again`() = test {
        source.rows.value = listOf(entity(1, downloadedAt = 100))
        val vm = vm()
        advanceUntilIdle()
        vm.update(1); advanceUntilIdle()
        assertEquals(listOf(Triple(1L, "T1", listOf("A"))), source.requested)
    }

    @Test fun totalBytesSums() = test {
        source.rows.value = listOf(
            entity(1, downloadedAt = 100, size = 1_000),
            entity(2, downloadedAt = 50, size = 2_500),
            entity(3, downloadedAt = null, hasCopy = false, size = 0, job = DownloadJob.FAILED),
        )
        val vm = vm()
        advanceUntilIdle()
        assertEquals(3_500L, vm.state.value.totalBytes)
        assertEquals(2, vm.state.value.copies)
    }

    @Test fun rowWithoutCopyHasNoBlurb() = test {
        source.rows.value = listOf(
            entity(1, downloadedAt = 100),
            entity(2, downloadedAt = null, hasCopy = false, job = DownloadJob.FAILED, failure = "Sign in to download this work"),
        )
        val vm = vm()
        advanceUntilIdle()
        val (withCopy, without) = vm.state.value.rows
        assertNotNull(withCopy.blurb)
        assertEquals("S1", (withCopy.summary.single() as Block.Text).text)
        assertNull(without.blurb)
        assertTrue(without.summary.isEmpty())
    }

    @Test fun malformedBlurbWithCopy_isOpenable() = test {
        source.rows.value = listOf(
            entity(1, downloadedAt = 100).copy(blurbJson = "{not json"),
            entity(2, downloadedAt = null, hasCopy = false, job = DownloadJob.FAILED),
        )
        val vm = vm()
        advanceUntilIdle()
        val (broken, without) = vm.state.value.rows
        assertNull(broken.blurb)        // shown as a pending-style row...
        assertTrue(broken.openable)     // ...that still opens the reader
        assertFalse(without.openable)
    }

    @Test fun malformedBlurbWithCopy_showsStoredDetails() = test {
        source.rows.value = listOf(
            entity(1, downloadedAt = 1_763_683_200_000, size = 40_960)
                .copy(blurbJson = "{not json", chaptersPostedRemote = 5, title = "Synthetic Title", authors = "Author One, Author Two"),
        )
        val vm = vm()
        advanceUntilIdle()
        val row = vm.state.value.rows.single()
        assertNull(row.blurb)
        val d = copyDetails(row.entity, java.time.ZoneOffset.UTC)!!
        assertEquals("3 chapters", d.chapters)
        assertEquals(2, d.newChapters)
        assertEquals("40 KB · 21 Nov 2025", d.sizeAndDate)
        assertEquals("Synthetic Title", row.entity.title)
        assertEquals("Author One, Author Two", row.entity.authors)
        // No copy: nothing stored to describe.
        assertNull(copyDetails(entity(2, downloadedAt = null, hasCopy = false, job = DownloadJob.FAILED)))
    }

    @Test fun `size rounds before choosing the unit`() {
        assertEquals("1023 KB", formatSize(1023L * 1024))
        assertEquals("1.0 MB", formatSize(1_048_575))
        assertEquals("1.0 MB", formatSize(1024L * 1024))
    }

    @Test fun `date uses a three-letter month`() {
        // 2026-09-14T12:00:00Z
        assertEquals("14 Sep 2026", downloadDate(1_789_387_200_000, java.time.ZoneOffset.UTC))
    }

    @Test fun `size and line formats`() {
        assertEquals("0 KB", formatSize(0))
        assertEquals("2 KB", formatSize(1_600))
        assertEquals("1023 KB", formatSize(1023L * 1024))
        assertEquals("1.0 MB", formatSize(1024L * 1024))
        assertEquals("2.5 MB", formatSize(2_621_440))
        assertEquals("12 chapters", chaptersLine(entity(1, downloadedAt = 1).copy(chaptersStored = 12)))
        assertEquals("1 chapter", chaptersLine(entity(1, downloadedAt = 1).copy(chaptersStored = 1, chaptersPostedRemote = 1)))
        assertEquals(2, newChapters(entity(1, downloadedAt = 1).copy(chaptersStored = 3, chaptersPostedRemote = 5)))
        assertNull(newChapters(entity(1, downloadedAt = 1).copy(chaptersStored = 3, chaptersPostedRemote = null)))
        assertEquals("3 works · 1.5 MB", totalLine(3, 1_572_864))
        assertEquals("1 work · 40 KB", totalLine(1, 40_960))
    }

    private fun blurb(id: Long) = WorkBlurb(
        workId = id, title = "T$id", authors = listOf("A"), fandoms = emptyList(), rating = null,
        warnings = emptyList(), categories = emptyList(), complete = null, relationships = emptyList(),
        characters = emptyList(), freeforms = emptyList(), summaryHtml = "<p>S$id</p>", language = null, words = null,
        chaptersPosted = null, chaptersTotal = null, kudos = null, hits = null, bookmarks = null,
        comments = null, updated = null,
    )

    private fun entity(
        id: Long, downloadedAt: Long?, hasCopy: Boolean = true, size: Long = 100, requestedAt: Long = 0,
        job: DownloadJob = DownloadJob.NONE, failure: String? = null,
    ) = DownloadEntity(
        workId = id, title = "T$id", authors = "A",
        blurbJson = if (hasCopy) json.encodeToString(WorkBlurb.serializer(), blurb(id)) else null,
        chaptersStored = if (hasCopy) 3 else 0, chaptersPostedRemote = null,
        source = if (hasCopy) SourceKind.FULL_WORK_PAGE else null, formatVersion = 1,
        downloadedAt = downloadedAt, sizeBytes = size, hasCopy = hasCopy, job = job, failure = failure,
        requestedAt = requestedAt,
    )

    private class FakeDownloadsSource : DownloadsSource {
        val rows = MutableStateFlow<List<DownloadEntity>>(emptyList())
        val deleted = mutableListOf<Long>()
        val requested = mutableListOf<Triple<Long, String, List<String>>>()
        override fun observeAll(): Flow<List<DownloadEntity>> = rows
        override suspend fun request(workId: Long, title: String, authors: List<String>) {
            requested += Triple(workId, title, authors)
        }
        override suspend fun delete(workId: Long) { deleted += workId }
    }
}
