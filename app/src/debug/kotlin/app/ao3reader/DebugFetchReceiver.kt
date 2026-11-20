package app.ao3reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Debug-only: `am broadcast -a app.ao3reader.DEBUG_FETCH --es path "/users/X/readings"` fetches signed in. */
class DebugFetchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "app.ao3reader.DEBUG_FETCH") return
        val path = intent.getStringExtra("path") ?: return
        val container = (context.applicationContext as App).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                if (path == "me") {
                    Log.i(TAG, "signedIn=${container.session.state.value.signedIn}")
                } else if (!path.startsWith("/") || path.startsWith("//")) {
                    Log.w(TAG, "rejected path")
                } else {
                    val url = ("https://archiveofourown.org" + path).toHttpUrlOrNull()
                    if (url == null || url.host != "archiveofourown.org") {
                        Log.w(TAG, "rejected path")
                    } else {
                        val r = container.session.authedGet(url, interactive = false)
                        Log.i(TAG, r::class.java.simpleName)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "failed: ${e::class.java.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object { const val TAG = "DebugFetch" }
}
