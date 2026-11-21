package app.ao3reader.data.download

import app.ao3reader.ao3.model.ChapterContent
import app.ao3reader.ao3.model.ChapterRef
import app.ao3reader.ao3.model.WorkBlurb
import app.ao3reader.ao3.net.FetchResult
import app.ao3reader.data.db.SourceKind

/** One way of getting a whole work (the full-work page now; EPUB later). */
interface DownloadSource {
    val kind: SourceKind
    suspend fun fetch(workId: Long, interactive: Boolean, sink: ChapterSink): SourceResult
}

/** Receives each chapter as it is read, in order, with its 0-based index. */
fun interface ChapterSink { fun chapter(index: Int, content: ChapterContent) }

sealed interface SourceResult {
    data class Ok(val blurb: WorkBlurb?, val chapters: List<ChapterRef>, val postedRemote: Int?) : SourceResult
    /** Try the next source. */
    data object Unavailable : SourceResult
    data class Failed(val reason: DownloadFailure, val result: FetchResult?) : SourceResult
}

enum class DownloadFailure(val permanent: Boolean, val message: String) {
    SIGN_IN_REQUIRED(true, "Sign in to download this work"),
    NOT_FOUND(true, "This work no longer exists on AO3."),
    UNREADABLE(true, "Couldn't read this work's page."),
    BLOCKED(false, "AO3 asked for a check. Open the app and tap Retry."),
    NETWORK(false, "Couldn't reach AO3. Tap Retry."),
}
