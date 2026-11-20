package app.ao3reader.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** True when the page is still a Cloudflare / AO3 robot challenge. Only the real title is matched. */
internal fun isChallengePage(title: String, html: String): Boolean =
    title.contains("Just a moment", ignoreCase = true) ||
        html.contains("may be a robot", ignoreCase = true)

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
