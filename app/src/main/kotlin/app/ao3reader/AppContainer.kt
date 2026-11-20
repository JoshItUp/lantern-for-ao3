package app.ao3reader

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import androidx.webkit.WebViewCompat
import app.ao3reader.account.CredentialStore
import app.ao3reader.account.ObservingFetcher
import app.ao3reader.account.Session
import app.ao3reader.account.keystoreSecretBox
import app.ao3reader.ao3.net.Ao3Fetcher
import app.ao3reader.ao3.net.Fetcher
import androidx.room.Room
import app.ao3reader.data.ChapterCache
import app.ao3reader.data.SearchRepository
import app.ao3reader.data.SettingsStore
import app.ao3reader.data.WorkRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import app.ao3reader.data.db.AppDatabase
import app.ao3reader.net.AnonymousCookieInterceptor
import app.ao3reader.net.DeferredFetcher
import app.ao3reader.net.PersistentCookieJar
import app.ao3reader.net.cachedUserAgent
import app.ao3reader.net.clearAo3SessionCookies
import app.ao3reader.net.WebViewChallengeSolver
import okhttp3.Cache
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

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(PersistentCookieJar { CookieManager.getInstance() })
            // Network level: runs after the bridge adds the jar's cookies and before it stores new ones.
            .addNetworkInterceptor(AnonymousCookieInterceptor())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .cache(Cache(File(context.cacheDir, "http"), 10L * 1024 * 1024))
            .build()
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
        Room.databaseBuilder(context, AppDatabase::class.java, "ao3reader.db").build()
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

    val workRepo: WorkRepository by lazy { WorkRepository(fetcher, chapterCache, appScope) }
}
