package dev.kitchenapp.core.domain.ingredient

import dev.kitchenapp.core.domain.identifiers.IngredientId
import kotlin.uuid.Uuid

/**
 * Small, hand-reviewed acceptance corpus for exact-name and curated-alias
 * normalization. Expected outcomes intentionally preserve ambiguity and
 * descriptive wording rather than guessing a base ingredient.
 */
internal object ReviewedIngredientNormalizationFixtures {
    private fun ingredientId(suffix: String): IngredientId =
        IngredientId(Uuid.parse("00000000-0000-0000-0000-000000000$suffix"))

    val yellowOnionId = ingredientId("001")
    val scallionId = ingredientId("002")
    val flourId = ingredientId("003")
    val butterId = ingredientId("004")
    val blackPepperId = ingredientId("005")
    val bellPepperId = ingredientId("006")
    val cilantroId = ingredientId("007")

    val catalog = listOf(
        Ingredient(
            id = yellowOnionId,
            name = "Yellow onion",
            aliases = setOf("onion", "onions", "allium cepa"),
        ),
        Ingredient(
            id = scallionId,
            name = "Scallion",
            aliases = setOf("green onion", "spring onion", "scallions"),
        ),
        Ingredient(
            id = flourId,
            name = "All-purpose flour",
            aliases = setOf("plain flour", "AP flour"),
        ),
        Ingredient(
            id = butterId,
            name = "Butter",
            aliases = setOf("unsalted butter"),
        ),
        Ingredient(
            id = blackPepperId,
            name = "Black pepper",
            aliases = setOf("pepper", "ground black pepper"),
        ),
        Ingredient(
            id = bellPepperId,
            name = "Bell pepper",
            aliases = setOf("sweet pepper", "pepper"),
        ),
        Ingredient(
            id = cilantroId,
            name = "Cilantro",
            aliases = setOf("coriander leaves"),
        ),
    )

    val cases = listOf(
        ReviewedNormalizationCase(
            id = "canonical-yellow-onion",
            category = "canonical",
            input = "Yellow onion",
            expected = ExpectedIngredientResolution.Resolved(yellowOnionId),
        ),
        ReviewedNormalizationCase(
            id = "canonical-scallion",
            category = "canonical",
            input = "Scallion",
            expected = ExpectedIngredientResolution.Resolved(scallionId),
        ),
        ReviewedNormalizationCase(
            id = "canonical-all-purpose-flour",
            category = "canonical",
            input = "All-purpose flour",
            expected = ExpectedIngredientResolution.Resolved(flourId),
        ),
        ReviewedNormalizationCase(
            id = "canonical-butter",
            category = "canonical",
            input = "Butter",
            expected = ExpectedIngredientResolution.Resolved(butterId),
        ),
        ReviewedNormalizationCase(
            id = "alias-onions",
            category = "curated-alias",
            input = "onions",
            expected = ExpectedIngredientResolution.Resolved(yellowOnionId),
        ),
        ReviewedNormalizationCase(
            id = "alias-botanical-onion-name",
            category = "curated-alias",
            input = "allium cepa",
            expected = ExpectedIngredientResolution.Resolved(yellowOnionId),
        ),
        ReviewedNormalizationCase(
            id = "alias-green-onion",
            category = "curated-alias",
            input = "green onion",
            expected = ExpectedIngredientResolution.Resolved(scallionId),
        ),
        ReviewedNormalizationCase(
            id = "alias-spring-onion",
            category = "curated-alias",
            input = "spring onion",
            expected = ExpectedIngredientResolution.Resolved(scallionId),
        ),
        ReviewedNormalizationCase(
            id = "alias-plain-flour",
            category = "curated-alias",
            input = "plain flour",
            expected = ExpectedIngredientResolution.Resolved(flourId),
        ),
        ReviewedNormalizationCase(
            id = "alias-ap-flour",
            category = "curated-alias",
            input = "AP flour",
            expected = ExpectedIngredientResolution.Resolved(flourId),
        ),
        ReviewedNormalizationCase(
            id = "alias-unsalted-butter",
            category = "curated-alias",
            input = "unsalted butter",
            expected = ExpectedIngredientResolution.Resolved(butterId),
        ),
        ReviewedNormalizationCase(
            id = "alias-coriander-leaves",
            category = "curated-alias",
            input = "coriander leaves",
            expected = ExpectedIngredientResolution.Resolved(cilantroId),
        ),
        ReviewedNormalizationCase(
            id = "punctuation-case-onion",
            category = "punctuation-and-case",
            input = " YELLOW-ONION!!! ",
            expected = ExpectedIngredientResolution.Resolved(yellowOnionId),
        ),
        ReviewedNormalizationCase(
            id = "punctuation-underscore-scallion",
            category = "punctuation-and-case",
            input = "GREEN_ONION",
            expected = ExpectedIngredientResolution.Resolved(scallionId),
        ),
        ReviewedNormalizationCase(
            id = "punctuation-case-flour",
            category = "punctuation-and-case",
            input = "ALL-PURPOSE flour",
            expected = ExpectedIngredientResolution.Resolved(flourId),
        ),
        ReviewedNormalizationCase(
            id = "punctuation-dot-black-pepper",
            category = "punctuation-and-case",
            input = "BLACK.PEPPER",
            expected = ExpectedIngredientResolution.Resolved(blackPepperId),
        ),
        ReviewedNormalizationCase(
            id = "punctuation-hyphen-bell-pepper",
            category = "punctuation-and-case",
            input = "bell-pepper",
            expected = ExpectedIngredientResolution.Resolved(bellPepperId),
        ),
        ReviewedNormalizationCase(
            id = "descriptive-large-onion",
            category = "descriptive-text",
            input = "large yellow onion",
            expected = ExpectedIngredientResolution.Unresolved,
        ),
        ReviewedNormalizationCase(
            id = "descriptive-preparation-onion",
            category = "descriptive-text",
            input = "finely chopped onion",
            expected = ExpectedIngredientResolution.Unresolved,
        ),
        ReviewedNormalizationCase(
            id = "descriptive-ripe-pepper",
            category = "descriptive-text",
            input = "ripe bell pepper",
            expected = ExpectedIngredientResolution.Unresolved,
        ),
        ReviewedNormalizationCase(
            id = "unknown-sumac",
            category = "unknown",
            input = "sumac",
            expected = ExpectedIngredientResolution.Unresolved,
        ),
        ReviewedNormalizationCase(
            id = "unknown-tamarind-paste",
            category = "unknown",
            input = "tamarind paste",
            expected = ExpectedIngredientResolution.Unresolved,
        ),
        ReviewedNormalizationCase(
            id = "ambiguous-pepper",
            category = "ambiguous-alias",
            input = "pepper",
            expected = ExpectedIngredientResolution.NeedsReview(
                listOf(bellPepperId, blackPepperId),
            ),
        ),
        ReviewedNormalizationCase(
            id = "ambiguous-punctuated-pepper",
            category = "ambiguous-alias",
            input = "PEPPER!",
            expected = ExpectedIngredientResolution.NeedsReview(
                listOf(bellPepperId, blackPepperId),
            ),
        ),
    )
}

internal data class ReviewedNormalizationCase(
    val id: String,
    val category: String,
    val input: String,
    val expected: ExpectedIngredientResolution,
)

internal sealed interface ExpectedIngredientResolution {
    data class Resolved(val ingredientId: IngredientId) : ExpectedIngredientResolution
    data object Unresolved : ExpectedIngredientResolution
    data class NeedsReview(val candidateIds: List<IngredientId>) : ExpectedIngredientResolution
}
