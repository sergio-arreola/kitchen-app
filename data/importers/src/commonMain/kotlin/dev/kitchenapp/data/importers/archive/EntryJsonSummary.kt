package dev.kitchenapp.data.importers.archive

/**
 * Schema-focused summary of one entry's top-level JSON object.
 * Holds key names, types, and sanitized shape examples only; never recipe text.
 */
data class EntryJsonSummary(
    val entryName: String,
    /** Top-level keys in document order. */
    val keys: List<String>,
    /** Keys explicitly set to null. */
    val nullKeys: Set<String>,
    /** Value type per key: "string", "number", "boolean", "array", "object". */
    val keyTypes: Map<String, String>,
    /** Shape-only example per key; scalar contents are never retained. */
    val examples: Map<String, String?>,
    /** Sanitized parse failure reason when the entry could not be parsed as a JSON object. */
    val parseError: String? = null,
)

/** Cross-entry frequency summary for a single JSON key. */
data class JsonKeyAggregate(
    val key: String,
    val entryCount: Int,
    val presentCount: Int,
    val nullCount: Int,
    val missingCount: Int,
    val typeCounts: Map<String, Int>,
    /** Up to [EXAMPLE_LIMIT] distinct sanitized examples. */
    val examples: List<String?>,
) {
    companion object {
        const val EXAMPLE_LIMIT = 2
    }
}

/** Aggregates per-entry summaries into key-frequency rows, sorted by first appearance. */
fun List<EntryJsonSummary>.aggregateJsonKeys(): List<JsonKeyAggregate> {
    val parsed = filter { it.parseError == null }
    if (parsed.isEmpty()) return emptyList()
    val order = parsed.flatMapTo(linkedSetOf()) { it.keys }
    return order.map { key ->
        val present = parsed.filter { key in it.keys }
        val nulls = present.count { key in it.nullKeys }
        val types = present.groupingBy { it.keyTypes[key] ?: "unknown" }.eachCount()
        val examples = present.mapNotNull { it.examples[key] }
            .distinct()
            .take(JsonKeyAggregate.EXAMPLE_LIMIT)
        JsonKeyAggregate(
            key = key,
            entryCount = parsed.size,
            presentCount = present.size,
            nullCount = nulls,
            missingCount = parsed.size - present.size,
            typeCounts = types,
            examples = examples,
        )
    }
}
