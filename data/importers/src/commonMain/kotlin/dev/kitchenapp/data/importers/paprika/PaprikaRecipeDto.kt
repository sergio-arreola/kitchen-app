package dev.kitchenapp.data.importers.paprika

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Transport DTO for the Paprika JSON recipe format. Never maps directly to domain entities. */
@Serializable
data class PaprikaRecipeDto(
    val uid: String? = null,
    val name: String? = null,
    val servings: String? = null,
    @SerialName("photo") val photoName: String? = null,
    @SerialName("photo_hash") val photoHash: String? = null,
    val categories: List<String> = emptyList(),
    val notes: String? = null,
    /** Source label, e.g. a site or book name. */
    val source: String? = null,
    @SerialName("source_url") val sourceUrl: String? = null,
    val ingredients: String? = null,
    val directions: String? = null,
)
