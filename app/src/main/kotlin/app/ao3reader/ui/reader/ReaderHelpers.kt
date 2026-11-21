package app.ao3reader.ui.reader

import app.ao3reader.ao3.net.FetchResult
import app.ao3reader.data.NotAChapterPageException
import app.ao3reader.data.download.NotDownloadedException

/** The reader's message for a failed load; every one is shown with a Retry button. */
internal fun readerErrorText(error: FetchResult): String = when {
    error is FetchResult.Blocked -> "AO3 is blocking requests right now."
    error is FetchResult.RateLimited -> "AO3 asked us to slow down. Try again in ${error.retryAfterSec} s."
    error is FetchResult.NotFound -> "This work isn't available (deleted or hidden)."
    // An Ok page without chapter text: in practice AO3's sign-in page for registered-users-only works.
    error is FetchResult.Failed && error.cause is NotAChapterPageException -> "This work isn't available signed out."
    // A chapter the download doesn't have, while AO3 can't be reached.
    error is FetchResult.Failed && error.cause is NotDownloadedException -> "Not downloaded · Update when online"
    else -> "Couldn't load this chapter."
}

/** Scroll reports while moving are coarsened to this many pixels; the resting position is exact. */
internal const val SCROLL_BUCKET_PX = 24

/**
 * Key that changes when the scroll position is worth reporting: the item and a [SCROLL_BUCKET_PX]
 * bucket of the offset while [scrolling], the exact offset once it stops (a separate key space,
 * so the stop always produces a new key). Packed in a Long rather than a Pair.
 */
internal fun scrollKey(index: Int, offsetPx: Int, scrolling: Boolean): Long {
    val low = if (scrolling) (offsetPx / SCROLL_BUCKET_PX).toLong() or (1L shl 31) else offsetPx.toLong()
    return (index.toLong() shl 32) or (low and 0xFFFFFFFFL)
}
