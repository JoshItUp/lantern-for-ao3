package io.github.joshitup.lantern.net

import android.webkit.CookieManager

private const val AO3_URL = "https://archiveofourown.org"

/**
 * Expires AO3's login cookies in the WebView store (shared with OkHttp through [PersistentCookieJar]):
 * the session ones, plus the legacy `user_credentials`. Cloudflare's cookies are left alone. Each is
 * expired both host-only and for the domain, since either form may have been stored.
 */
fun clearAo3SessionCookies(cm: CookieManager) {
    for (name in AO3_SESSION_COOKIES + "user_credentials") {
        cm.setCookie(AO3_URL, "$name=; Max-Age=0; Path=/")
        cm.setCookie(AO3_URL, "$name=; Max-Age=0; Path=/; Domain=.archiveofourown.org")
    }
    cm.flush()
}
