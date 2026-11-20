package app.ao3reader.net

import android.content.SharedPreferences

private const val KEY_UA = "ua"
private const val KEY_VERSION = "webview_version"

/**
 * The WebView user agent, stored with the WebView version it came from. Computing it loads the
 * whole Chromium stack, so it is only recomputed when the WebView package changes (or its version
 * is unknown).
 */
internal fun cachedUserAgent(prefs: SharedPreferences, webViewVersion: String?, compute: () -> String): String {
    if (webViewVersion != null && prefs.getString(KEY_VERSION, null) == webViewVersion) {
        prefs.getString(KEY_UA, null)?.let { return it }
    }
    val ua = compute()
    if (webViewVersion != null) {
        prefs.edit().putString(KEY_UA, ua).putString(KEY_VERSION, webViewVersion).apply()
    }
    return ua
}
