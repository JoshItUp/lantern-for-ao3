package app.ao3reader.data.download

import app.ao3reader.ao3.net.FetchResult
import app.ao3reader.ao3.net.Fetcher
import app.ao3reader.ao3.parse.Ao3Urls
import app.ao3reader.ao3.parse.FullWorkMeta
import app.ao3reader.ao3.parse.FullWorkParser
import app.ao3reader.data.AuthedFetch
import app.ao3reader.data.db.SourceKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Downloads from `/works/{id}?view_full_work=true`. Fetches anonymously; only when that is not a work page
 * and an account is acting does it retry once signed in.
 */
class FullWorkPageSource(
    private val fetcher: Fetcher,
    private val authed: AuthedFetch?,
    private val parse: CoroutineDispatcher,
) : DownloadSource {
    override val kind = SourceKind.FULL_WORK_PAGE

    override suspend fun fetch(workId: Long, interactive: Boolean, sink: ChapterSink): SourceResult {
        val url = Ao3Urls.fullWork(workId)
        var result = fetcher.get(url, interactive = interactive, anonymous = true)
        var meta = parseOk(result, workId, sink)
        if (meta == null && result is FetchResult.Ok && authed?.isActing() == true) {
            result = authed.get(url, interactive, forceNetwork = true)
            meta = parseOk(result, workId, sink)
        }
        if (meta == null) {
            val reason = when (result) {
                is FetchResult.Ok -> if (authed?.isActing() == true) DownloadFailure.UNREADABLE else DownloadFailure.SIGN_IN_REQUIRED
                FetchResult.NotFound -> DownloadFailure.NOT_FOUND
                FetchResult.Blocked -> DownloadFailure.BLOCKED
                is FetchResult.Failed, is FetchResult.RateLimited -> DownloadFailure.NETWORK
            }
            return SourceResult.Failed(reason, result.takeIf { it !is FetchResult.Ok })
        }
        // AO3 can answer the full-work URL with a single chapter page; the posted count catches that.
        val posted = meta.blurb.chaptersPosted
        if (posted != null && posted != meta.chapters.size) return SourceResult.Failed(DownloadFailure.UNREADABLE, null)
        return SourceResult.Ok(meta.blurb, meta.chapters, posted)
    }

    private suspend fun parseOk(result: FetchResult, workId: Long, sink: ChapterSink): FullWorkMeta? {
        if (result !is FetchResult.Ok) return null
        return withContext(parse) {
            var i = 0
            FullWorkParser.parse(result.html, workId) { sink.chapter(i++, it) }
        }
    }
}
