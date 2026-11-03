package dev.kitchenapp.data.importers.archive

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Parses entry JSON into sanitized schema summaries. Uses [JsonElement] only; no DTOs. */
class PaprikaJsonInspector {

    fun summarize(entryName: String, jsonBytes: ByteArray): EntryJsonSummary {
        val element = try {
            lenientJson.parseToJsonElement(jsonBytes.decodeToString())
        } catch (e: Exception) {
            return EntryJsonSummary(
                entryName = entryName,
                keys = emptyList(),
                nullKeys = emptySet(),
                keyTypes = emptyMap(),
                examples = emptyMap(),
                parseError = e::class.simpleName,
            )
        }
        val obj = element as? JsonObject
            ?: return EntryJsonSummary(
                entryName = entryName,
                keys = emptyList(),
                nullKeys = emptySet(),
                keyTypes = emptyMap(),
                examples = emptyMap(),
                parseError = "NotJsonObject",
            )

        val types = mutableMapOf<String, String>()
        val examples = mutableMapOf<String, String?>()
        val nulls = mutableSetOf<String>()
        obj.forEach { (key, value) ->
            types[key] = typeName(value)
            examples[key] = exampleOf(value)
            if (value is JsonNull) nulls.add(key)
        }
        return EntryJsonSummary(entryName, obj.keys.toList(), nulls, types, examples)
    }

    private fun typeName(element: JsonElement): String = when (element) {
        is JsonNull -> "null"
        is JsonArray -> "array"
        is JsonObject -> "object"
        is JsonPrimitive -> when {
            element.isString -> "string"
            element.content == "true" || element.content == "false" -> "boolean"
            else -> "number"
        }
    }

    private fun exampleOf(element: JsonElement): String? = when (element) {
        is JsonNull -> null
        is JsonArray -> {
            val parts = element.distinctBy { typeName(it) }.joinToString("|") { typeName(it) }
            "array[$parts]×${element.size}"
        }
        is JsonObject -> "object{${element.keys.take(KEY_SAMPLE_LIMIT).joinToString(",")}}"
        is JsonPrimitive -> {
            val content = element.content
            when {
                content.contains('\n') -> "string with ${content.lines().size} lines"
                element.isString -> content.take(SCALAR_LIMIT)
                else -> content.take(SCALAR_LIMIT)
            }
        }
    }

    private val lenientJson = Json

    private companion object {
        const val SCALAR_LIMIT = 40
        const val KEY_SAMPLE_LIMIT = 6
    }
}
