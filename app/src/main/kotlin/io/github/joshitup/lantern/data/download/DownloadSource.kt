package io.github.joshitup.lantern.data.download

import io.github.joshitup.lantern.ao3.model.ChapterContent
import io.github.joshitup.lantern.ao3.model.ChapterRef
import io.github.joshitup.lantern.ao3.model.WorkBlurb
import io.github.joshitup.lantern.ao3.net.FetchResult
import io.github.joshitup.lantern.data.db.SourceKind

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

enum class DownloadFailure(val message: String) {
    SIGN_IN_REQUIRED("Sign in to download this work"),
    NOT_FOUND("This work no longer exists on AO3."),
    UNREADABLE("Couldn't read this work's page."),
    BLOCKED("AO3 asked for a check. Tap Retry."),
    NETWORK("Couldn't reach AO3. Tap Retry."),
}
