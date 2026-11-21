package app.ao3reader.ui.reader

import app.ao3reader.ao3.net.FetchResult
import app.ao3reader.data.NotAChapterPageException
import app.ao3reader.data.download.NotDownloadedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.IOException

class ReaderHelpersTest {
    @Test fun `each error has its own message`() {
        assertEquals("AO3 is blocking requests right now.", readerErrorText(FetchResult.Blocked))
        assertEquals("AO3 asked us to slow down. Try again in 120 s.", readerErrorText(FetchResult.RateLimited(120)))
        assertEquals("This work isn't available (deleted or hidden).", readerErrorText(FetchResult.NotFound))
        assertEquals("This work isn't available signed out.", readerErrorText(FetchResult.Failed(NotAChapterPageException())))
        assertEquals("Couldn't load this chapter.", readerErrorText(FetchResult.Failed(IOException("HTTP 500"))))
        assertEquals("Couldn't load this chapter.", readerErrorText(FetchResult.Failed(null)))
        assertEquals("Not downloaded · Update when online", readerErrorText(FetchResult.Failed(NotDownloadedException())))
    }

    @Test fun `scroll key changes per bucket while scrolling and exactly at rest`() {
        assertEquals(scrollKey(3, 0, true), scrollKey(3, SCROLL_BUCKET_PX - 1, true))
        assertNotEquals(scrollKey(3, 0, true), scrollKey(3, SCROLL_BUCKET_PX, true))
        assertNotEquals(scrollKey(3, 5, true), scrollKey(4, 5, true))
        assertNotEquals(scrollKey(3, 5, false), scrollKey(3, 6, false))
        // Stopping inside the same bucket still yields a new key, so the exact rest position is reported.
        assertNotEquals(scrollKey(3, 5, true), scrollKey(3, 5, false))
        assertNotEquals(scrollKey(0, 0, true), scrollKey(0, 0, false))
    }
}
