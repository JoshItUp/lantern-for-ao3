package io.github.joshitup.lantern

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import androidx.webkit.WebViewCompat
import io.github.joshitup.lantern.account.CredentialStore
import io.github.joshitup.lantern.account.ObservingFetcher
import io.github.joshitup.lantern.account.Session
import io.github.joshitup.lantern.account.keystoreSecretBox
import io.github.joshitup.lantern.ao3.net.Ao3Fetcher
import io.github.joshitup.lantern.ao3.net.Fetcher
import androidx.room.Room
import io.github.joshitup.lantern.data.ActionRepository
import io.github.joshitup.lantern.data.ActionRunner
import io.github.joshitup.lantern.data.AuthedFetch
import io.github.joshitup.lantern.data.ChapterCache
import io.github.joshitup.lantern.data.LibraryRepository
import io.github.joshitup.lantern.data.SearchRepository
import io.github.joshitup.lantern.data.SettingsStore
import io.github.joshitup.lantern.data.WorkListRepository
import io.github.joshitup.lantern.data.WorkRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import io.github.joshitup.lantern.data.db.AppDatabase
import io.github.joshitup.lantern.data.db.MIGRATION_2_3
import io.github.joshitup.lantern.ao3.parse.WorkMetaParser
import io.github.joshitup.lantern.data.ChapterSource
import io.github.joshitup.lantern.data.download.DownloadAwareChapterSource
import io.github.joshitup.lantern.data.download.DownloadRepository
import io.github.joshitup.lantern.data.download.DownloadStore
import io.github.joshitup.lantern.data.download.DownloadWorker
import io.github.joshitup.lantern.data.download.Downloader
import io.github.joshitup.lantern.data.download.FullWorkPageSource
import io.github.joshitup.lantern.net.AnonymousCookieInterceptor
import io.github.joshitup.lantern.net.DeferredFetcher
import io.github.joshitup.lantern.net.PersistentCookieJar
import io.github.joshitup.lantern.net.cachedUserAgent
import io.github.joshitup.lantern.net.clearAo3SessionCookies
import io.github.joshitup.lantern.net.WebViewChallengeSolver
import okhttp3.Cache
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manual dependency container. Nothing here may touch WebView or CookieManager at app start: the
 * network stack is built by [DeferredFetcher] on the first request, off the main thread.
 */
class AppContainer(val context: Context) {
    /** Cached per WebView version; computing it loads Chromium. */
    val userAgent: String by lazy {
        cachedUserAgent(
            context.getSharedPreferences("webview", Context.MODE_PRIVATE),
            WebViewCompat.getCurrentWebViewPackage(context)?.versionName,
        ) { WebSettings.getDefaultUserAgent(context) }
    }

    private val okHttpLazy = lazy {
        OkHttpClient.Builder()
            .cookieJar(PersistentCookieJar { CookieManager.getInstance() })
            // Network level: runs after the bridge adds the jar's cookies and before it stores new ones.
            .addNetworkInterceptor(AnonymousCookieInterceptor())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .cache(Cache(File(context.cacheDir, "http"), 10L * 1024 * 1024))
            .build()
    }
    val okHttpClient: OkHttpClient by okHttpLazy

    /** The client if something already built it; null otherwise (and nothing is started). */
    fun okHttpClientIfBuilt(): OkHttpClient? = if (okHttpLazy.isInitialized()) okHttpLazy.value else null

    /**
     * Drops every response in the HTTP disk cache (on sign-out: signed-in pages may be in it). A client
     * that was never built has written nothing this run, and building one would start the network stack.
     */
    suspend fun evictHttpCache() {
        val client = okHttpClientIfBuilt() ?: return
        withContext(Dispatchers.IO) { client.cache?.evictAll() }
    }

    /** The network chain without account observation; [session] uses it for its own requests. */
    private val baseFetcher: Fetcher by lazy {
        DumpingFetcher(
            DeferredFetcher { Ao3Fetcher(okHttpClient, userAgent, WebViewChallengeSolver(context, userAgent)) },
            pageDump(context),
        )
    }

    /** Every signed-in page the app loads updates [session]'s account state (reached lazily: no construction cycle). */
    val fetcher: Fetcher by lazy { ObservingFetcher(baseFetcher) { html -> session.observe(html) } }

    val db: AppDatabase by lazy {
        // Legacy file name kept on purpose: renaming it would need a migration, and the one-time reinstall resets data anyway.
        Room.databaseBuilder(context, AppDatabase::class.java, "ao3reader.db")
            .addMigrations(MIGRATION_2_3).build()
    }

    val settings: SettingsStore by lazy { SettingsStore(context) }

    val chapterCache: ChapterCache by lazy { ChapterCache(File(context.cacheDir, "chapters")) }

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val session: Session by lazy {
        Session(
            baseFetcher,
            CredentialStore(context.getSharedPreferences("account", Context.MODE_PRIVATE), keystoreSecretBox()),
            { clearAo3SessionCookies(CookieManager.getInstance()) },
            appScope,
        )
    }

    val searchRepo: SearchRepository by lazy { SearchRepository(fetcher) }

    /** Signed-in fetches through [session], for chapters and downloads. */
    val authedFetch: AuthedFetch = object : AuthedFetch {
        override fun isActing() = session.actingAccount.value != null
        override suspend fun get(url: HttpUrl, interactive: Boolean, forceNetwork: Boolean) =
            session.authedGet(url, interactive = interactive, forceNetwork = forceNetwork)
    }

    /** Chapters a signed-in owner opens are fetched (or visited) signed in, for AO3 History; prefetches go signed out. */
    val workRepo: WorkRepository by lazy {
        WorkRepository(
            fetcher, chapterCache, appScope,
            authed = authedFetch,
            actions = { workId, html, pageUrl -> actionRepo.update(workId, html, pageUrl) },
        ).also { repo ->
            // A signed-in chapter page carries AO3's posted-chapter count: "N new chapters" for downloaded works.
            repo.onChapterPage = { workId, html ->
                appScope.launch {
                    // Checked first: parsing builds a second full DOM, wasted on works that aren't downloaded.
                    if (db.downloadDao().get(workId)?.hasCopy == true) {
                        WorkMetaParser.postedChapters(html)?.let { downloadRepo.notePostedRemote(workId, it) }
                    }
                }
            }
        }
    }

    /** What the reader reads through: a downloaded work's stored chapters, everything else online via [workRepo]. */
    val chapterSource: ChapterSource by lazy {
        DownloadAwareChapterSource(workRepo, db.downloadDao(), downloadStore, downloadRepo, appScope, authedFetch::isActing)
    }

    val libraryRepo: LibraryRepository by lazy { LibraryRepository(session, db) }

    /** Kudos, bookmarks, mark for later, subscriptions (works, series, authors) and comments. */
    val actionRepo: ActionRepository by lazy { ActionRepository(session, appScope) }

    /** Account actions run here, not in a screen's scope: navigating away never cancels a POST. */
    val actionRunner: ActionRunner by lazy { ActionRunner(appScope) }

    /** Signed in while an account acts (restricted works show), else signed out. */
    val workListRepo: WorkListRepository by lazy {
        WorkListRepository(
            { session.actingAccount.value != null }, { session.authedGet(it) }, { fetcher.get(it) },
            signedInPage = { list, html, pageUrl -> actionRepo.updateList(list, html, pageUrl) },
        )
    }

    /** Downloaded works; app data, never cache (clearing the chapter cache or signing out never touches them). */
    val downloadStore: DownloadStore by lazy {
        DownloadStore(File(context.filesDir, "downloads"), db.downloadDao(), Json { ignoreUnknownKeys = true }, Dispatchers.IO)
    }

    /** The worker's downloader; one at a time, through the shared [fetcher]. */
    val downloader: Downloader by lazy {
        Downloader(listOf(FullWorkPageSource(fetcher, authedFetch, Dispatchers.IO)), downloadStore, db.downloadDao())
    }

    val downloadRepo: DownloadRepository by lazy {
        DownloadRepository(db.downloadDao(), downloadStore, { DownloadWorker.enqueue(context) })
    }

    init {
        // Before the worker or any queue call runs: clear leftover temp dirs and fail rows a dead process left.
        // A failed clean-up must not crash the app; [DownloadRepository.ready] completes either way.
        appScope.launch {
            runCatching { downloadRepo.startup() }
                .onFailure { e -> Log.w(TAG, "download startup failed: ${e::class.java.simpleName}") }
        }
    }

    private companion object {
        const val TAG = "AppContainer"
    }
}
