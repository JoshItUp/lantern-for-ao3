package app.ao3reader.net

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Uses the WebView [CookieManager] as the only store, so OkHttp and WebView share cookies.
 * [CookieManager.getInstance] loads the WebView stack, so the manager is only obtained on the first
 * request (OkHttp calls the jar on its own threads, never the main thread).
 */
class PersistentCookieJar(manager: () -> CookieManager) : CookieJar {
    constructor(manager: CookieManager) : this({ manager })

    private val cookieManager by lazy(manager)

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val u = url.toString()
        cookies.forEach { cookieManager.setCookie(u, it.toString()) }
        cookieManager.flush()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val header = cookieManager.getCookie(url.toString()) ?: return emptyList()
        return header.split(";").mapNotNull { Cookie.parse(url, it.trim()) }
    }
}
