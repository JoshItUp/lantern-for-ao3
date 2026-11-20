package app.ao3reader.ao3.parse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object Autocomplete {
    /**
     * `[{"id":"X","name":"X"}]` -> names. Lenient: a plain string element is used as-is, and an object
     * without `name` falls back to `id`. Invalid input yields an empty list.
     */
    fun parse(json: String): List<String> = runCatching {
        val arr = Json.parseToJsonElement(json) as? JsonArray ?: return@runCatching emptyList()
        arr.mapNotNull { el -> nameOf(el)?.takeIf { it.isNotBlank() } }
    }.getOrDefault(emptyList())

    private fun nameOf(el: JsonElement): String? = when (el) {
        is JsonObject -> el.stringAt("name") ?: el.stringAt("id")
        is JsonPrimitive -> el.takeIf { it.isString }?.content
        else -> null
    }

    private fun JsonObject.stringAt(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
}
