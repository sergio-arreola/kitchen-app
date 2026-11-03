package dev.kitchenapp.core.domain.ingredient

import dev.kitchenapp.core.domain.ingredient.IngredientResolution.NeedsReview
import dev.kitchenapp.core.domain.ingredient.IngredientResolution.Resolved
import dev.kitchenapp.core.domain.ingredient.IngredientResolution.Unresolved
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IngredientResolverTest {
    @Test
    fun reviewedCorpusMeetsAtLeast95PercentExactOutcomeAccuracy() {
        val resolver = IngredientResolver(ReviewedIngredientNormalizationFixtures.catalog)
        val cases = ReviewedIngredientNormalizationFixtures.cases
        val expectedCategories = setOf(
            "canonical",
            "curated-alias",
            "punctuation-and-case",
            "descriptive-text",
            "unknown",
            "ambiguous-alias",
        )
        assertEquals(expectedCategories, cases.map { it.category }.toSet())

        val failures = cases.mapNotNull { case ->
            val actual = resolver.resolve(case.input)
            val outcomeMatches = expectedOutcome(actual) == case.expected
            val inputPreserved = actual.input == case.input
            if (outcomeMatches && inputPreserved) {
                null
            } else {
                "${case.id} (${case.category}): expected=${case.expected}, actual=${expectedOutcome(actual)}, " +
                    "rawInputPreserved=$inputPreserved"
            }
        }
        val accuracy = (cases.size - failures.size).toDouble() / cases.size

        assertTrue(
            accuracy >= 0.95,
            "Reviewed normalization accuracy was ${accuracy * 100}% across ${cases.size} cases. " +
                "Mismatches: $failures",
        )
        assertTrue(
            failures.isEmpty(),
            "Every reviewed fixture should keep its expected outcome and raw input: $failures",
        )
    }

    @Test
    fun normalizerCollapsesWhitespaceAndPunctuationWithoutDroppingWords() {
        assertEquals("red onion diced", IngredientNameNormalizer.normalize(" Red-onion, diced "))
        assertTrue(IngredientNameNormalizer.normalize(" — ").isEmpty())
    }

    private fun expectedOutcome(
        resolution: IngredientResolution,
    ): ExpectedIngredientResolution = when (resolution) {
        is Resolved -> ExpectedIngredientResolution.Resolved(resolution.ingredient.id)
        is Unresolved -> ExpectedIngredientResolution.Unresolved
        is NeedsReview -> ExpectedIngredientResolution.NeedsReview(
            resolution.candidates.map { it.id },
        )
    }
}
