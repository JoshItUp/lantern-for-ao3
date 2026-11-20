package app.ao3reader.ao3.parse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

object Autocomplete {
    /** `[{"id":"X","name":"X"}]` -> names. Invalid input yields an empty list. */
    fun parse(json: String): List<String> = runCatching {
        val arr = Json.parseToJsonElement(json) as? JsonArray ?: return@runCatching emptyList()
        arr.mapNotNull { el ->
            val name = (el as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
            name?.takeIf { it.isNotBlank() }
        }
    }.getOrDefault(emptyList())
}
