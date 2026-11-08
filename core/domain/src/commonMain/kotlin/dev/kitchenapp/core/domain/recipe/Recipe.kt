package dev.kitchenapp.core.domain.recipe

import dev.kitchenapp.core.domain.identifiers.RecipeId
import dev.kitchenapp.core.domain.time.DomainInstant

/** Source-independent saved recipe. */
data class Recipe(
    val id: RecipeId = RecipeId.generate(),
    val name: String,
    val servings: String? = null,
    val ingredients: List<RecipeIngredient>,
    val directionsText: String,
    val notes: String? = null,
    val categories: List<String> = emptyList(),
    val sourceName: String? = null,
    val sourceUrl: String? = null,
    val prepTimeSeconds: Long? = null,
    val cookTimeSeconds: Long? = null,
    val chillingTimeSeconds: Long? = null,
    val totalTimeSeconds: Long? = null,
    val imageSourceUrl: String? = null,
    val importedAt: DomainInstant? = null,
) {
    init {
        require(name.isNotBlank()) { "Recipe name cannot be blank" }
        require(ingredients.map { it.id }.distinct().size == ingredients.size) {
            "Recipe contains duplicate ingredient row ids"
        }
        require(listOf(prepTimeSeconds, cookTimeSeconds, chillingTimeSeconds, totalTimeSeconds)
            .all { it == null || it >= 0 }
        ) {
            "Recipe times cannot be negative"
        }
    }
}

/** Locally retained source image bytes with value equality for repository round trips. */
class RecipeImage(bytes: ByteArray, val mediaType: String) {
    val bytes: ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is RecipeImage && mediaType == other.mediaType && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * mediaType.hashCode() + bytes.contentHashCode()
}
