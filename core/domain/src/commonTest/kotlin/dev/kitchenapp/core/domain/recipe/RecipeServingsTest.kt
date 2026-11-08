package dev.kitchenapp.core.domain.recipe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecipeServingsTest {
    @Test
    fun collapsesEquivalentValuesAndKeepsTheClearerLabel() {
        assertEquals("6 servings", normalizeRecipeServings(listOf("6", "6 servings")))
        assertEquals("6 servings", normalizeRecipeServings(listOf("Serves 6", "6 servings")))
    }

    @Test
    fun preservesMeaningfulRanges() {
        assertEquals("4-6 servings", normalizeRecipeServings(listOf("4-6 servings")))
    }

    @Test
    fun formatsLegacyDuplicateValuesWithoutRepeatingServings() {
        assertEquals("Servings: 6 servings", formatRecipeServings("6, 6 servings"))
        assertEquals("Serves 6", formatRecipeServings("6"))
        assertNull(formatRecipeServings(null))
    }
}
