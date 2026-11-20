package app.ao3reader.data

import app.ao3reader.ao3.model.SearchQuery
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// ignoreUnknownKeys so presets saved by an older/newer app version still load.
private val json = Json { ignoreUnknownKeys = true }

fun SearchQuery.toJson(): String = json.encodeToString(this)

fun searchQueryFromJson(s: String): SearchQuery = json.decodeFromString(s)
