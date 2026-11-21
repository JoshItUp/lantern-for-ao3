package app.ao3reader.ui.reader

import app.ao3reader.data.db.DownloadEntity
import app.ao3reader.data.db.DownloadJob
import app.ao3reader.data.db.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadLabelTest {
    private fun row(
        hasCopy: Boolean = false, job: DownloadJob = DownloadJob.NONE, stored: Int = 0, remote: Int? = null,
    ) = DownloadEntity(1L, "T", "A", null, stored, remote, SourceKind.FULL_WORK_PAGE, 1, 1L, 10L, hasCopy, job, null, 1L)

    @Test fun never_downloaded() {
        assertEquals("Download", downloadLabel(null))
        assertEquals("Download", downloadLabel(row()))
    }

    @Test fun jobs() {
        assertEquals("Queued…", downloadLabel(row(job = DownloadJob.QUEUED)))
        assertEquals("Downloading…", downloadLabel(row(job = DownloadJob.DOWNLOADING)))
        assertEquals("Retry", downloadLabel(row(job = DownloadJob.FAILED)))
        assertEquals("Queued…", downloadLabel(row(hasCopy = true, stored = 3, remote = 5, job = DownloadJob.QUEUED)))
    }

    @Test fun copy_states() {
        assertEquals("Downloaded ✓", downloadLabel(row(hasCopy = true, stored = 3, remote = 3)))
        assertEquals("Downloaded ✓", downloadLabel(row(hasCopy = true, stored = 3, remote = null)))
        assertEquals("Update (2 new)", downloadLabel(row(hasCopy = true, stored = 3, remote = 5)))
    }

    @Test fun new_chapters_line() {
        assertNull(newChaptersLine(null))
        assertNull(newChaptersLine(row(hasCopy = true, stored = 3, remote = 3)))
        assertNull(newChaptersLine(row(hasCopy = true, stored = 3, remote = null)))
        assertEquals("1 new chapter · Update", newChaptersLine(row(hasCopy = true, stored = 3, remote = 4)))
        assertEquals("2 new chapters · Update", newChaptersLine(row(hasCopy = true, stored = 3, remote = 5)))
    }

    @Test fun menu_item_state() {
        val fresh = downloadUi(null, showNewChapters = true)
        assertEquals(DownloadUi("Download", enabled = true, done = false, failure = null, newLine = null), fresh)
        assertFalse(downloadUi(row(job = DownloadJob.QUEUED), true).enabled)
        assertFalse(downloadUi(row(job = DownloadJob.DOWNLOADING), true).enabled)
        val done = downloadUi(row(hasCopy = true, stored = 3, remote = 3), true)
        assertFalse(done.enabled); assertTrue(done.done)
        val failed = downloadUi(row(job = DownloadJob.FAILED).copy(failure = "Synthetic failure reason"), true)
        assertEquals("Retry", failed.label); assertTrue(failed.enabled)
        assertEquals("Synthetic failure reason", failed.failure); assertNull(failed.newLine)
        val update = downloadUi(row(hasCopy = true, stored = 3, remote = 5), true)
        assertEquals("Update (2 new)", update.label); assertTrue(update.enabled)
        assertEquals("2 new chapters · Update", update.newLine)
        assertNull(downloadUi(row(hasCopy = true, stored = 3, remote = 5), false).newLine)
    }
}
