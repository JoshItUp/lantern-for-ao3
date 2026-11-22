package app.ao3reader

import android.app.Application

class App : Application() {
    /**
     * Built on first use (synchronized), not only in [onCreate]: WorkManager can create a rescheduled
     * [app.ao3reader.data.download.DownloadWorker] before onCreate has run. Building it needs only the base
     * context; [AppContainer] touches no WebView or network at construction.
     */
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        container // Built at start.
    }
}
