package app.ao3reader.net

import app.ao3reader.ao3.net.isRobotPage
import app.ao3reader.ao3.parse.Ao3Urls
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * True when the page is still a Cloudflare / AO3 robot challenge. Only the real title is matched, and
 * the robot sentence only outside work text (see [isRobotPage]).
 */
internal fun isChallengePage(title: String, html: String): Boolean =
    title.contains("Just a moment", ignoreCase = true) || isRobotPage(html)

private val CLOUDFLARE_HOSTS = setOf("challenges.cloudflare.com")

/** Where the challenge WebView may navigate: https AO3 (and its subdomains) and Cloudflare's challenge host. */
internal fun isAllowedChallengeUrl(url: String?): Boolean {
    if (url == null || !url.startsWith("https://", ignoreCase = true)) return false
    val host = url.toHttpUrlOrNull()?.host ?: return false   // already lowercased
    return host == Ao3Urls.HOST || host.endsWith(".${Ao3Urls.HOST}") || host in CLOUDFLARE_HOSTS
}

/**
 * Decodes the evaluateJavascript result of `JSON.stringify([document.title, html])`.
 * The result itself is a JSON string literal containing that JSON text. Returns null if undecodable.
 */
internal fun decodeTitleAndHtml(raw: String?): Pair<String, String>? {
    if (raw.isNullOrEmpty() || raw == "null") return null
    return try {
        val inner = Json.parseToJsonElement(raw).jsonPrimitive.content
        val arr = Json.parseToJsonElement(inner).jsonArray
        arr[0].jsonPrimitive.content to arr[1].jsonPrimitive.content
    } catch (e: Exception) {
        null
    }
}
