package app.ao3reader

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import app.ao3reader.ao3.net.Ao3Fetcher
import androidx.room.Room
import app.ao3reader.data.ChapterCache
import app.ao3reader.data.SettingsStore
import app.ao3reader.data.db.AppDatabase
import app.ao3reader.net.PersistentCookieJar
import app.ao3reader.net.WebViewChallengeSolver
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Manual dependency container. Later tasks add properties. */
class AppContainer(val context: Context) {
    val userAgent: String by lazy { WebSettings.getDefaultUserAgent(context) }

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(PersistentCookieJar(CookieManager.getInstance()))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .cache(Cache(File(context.cacheDir, "http"), 10L * 1024 * 1024))
            .build()
    }

    val fetcher: Ao3Fetcher by lazy {
        Ao3Fetcher(okHttpClient, userAgent, WebViewChallengeSolver(context, userAgent))
    }

    val db: AppDatabase by lazy {
        Room.databaseBuilder(context, AppDatabase::class.java, "ao3reader.db").build()
    }

    val settings: SettingsStore by lazy { SettingsStore(context) }

    val chapterCache: ChapterCache by lazy { ChapterCache(File(context.cacheDir, "chapters")) }
}
