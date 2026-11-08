package dev.kitchenapp.data.importers.paprika

import dev.kitchenapp.data.importers.ingredient.ParsedIngredient

/** Format-agnostic intermediate between a transport DTO and a future domain `Recipe`. */
data class RecipeDraft(
    /** Archive entry this draft came from, for traceability. */
    val sourceEntryName: String,
    /** Source-format identifier (Paprika uid); null when the record lacked one. */
    val externalId: String?,
    val name: String,
    val servings: String?,
    /** Raw ingredient lines, blanks removed; content untouched. */
    val ingredientLines: List<String>,
    /** Structured parse of [ingredientLines], same order. */
    val parsedIngredients: List<ParsedIngredient> = emptyList(),
    val directionsText: String,
    val notes: String?,
    val categories: List<String>,
    val sourceName: String?,
    val sourceUrl: String?,
    val photoFileName: String?,
    val photoHash: String?,
    val prepTimeSeconds: Long? = null,
    val cookTimeSeconds: Long? = null,
    val chillingTimeSeconds: Long? = null,
    val totalTimeSeconds: Long? = null,
    val imageSourceUrl: String? = null,
)
