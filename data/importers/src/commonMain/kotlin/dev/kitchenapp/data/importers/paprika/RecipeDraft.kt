package dev.kitchenapp.data.importers.paprika

/**
 * Format-agnostic intermediate between a transport DTO and a future domain
 * `Recipe`. Ingredient lines stay raw; parsing happens in a later slice.
 */
data class RecipeDraft(
    /** Archive entry this draft came from, for traceability. */
    val sourceEntryName: String,
    /** Source-format identifier (Paprika uid); null when the record lacked one. */
    val externalId: String?,
    val name: String,
    val servings: String?,
    /** Raw, unparsed ingredient lines. Blank lines removed, content untouched. */
    val ingredientLines: List<String>,
    val directionsText: String,
    val notes: String?,
    val categories: List<String>,
    val sourceName: String?,
    val sourceUrl: String?,
    val photoFileName: String?,
    val photoHash: String?,
)
