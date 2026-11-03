package dev.kitchenapp.data.importers.paprika

import dev.kitchenapp.core.domain.ingredient.Ingredient
import dev.kitchenapp.core.domain.ingredient.IngredientResolution
import dev.kitchenapp.core.domain.ingredient.IngredientResolver
import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.core.domain.units.UnitCode
import dev.kitchenapp.data.importers.ingredient.IngredientLineParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CanonicalRecipeDraftMapperTest {
    @Test
    fun mapsCanonicalMatchAndKeepsSourceIngredientLine() {
        val butter = Ingredient(name = "Butter", aliases = setOf("unsalted butter"))
        val draft = recipeDraft("2 tbsp unsalted butter, melted")
        val canonical = draft.toCanonicalRecipeDraft(IngredientResolver(listOf(butter)))

        assertEquals("Quick sauce", canonical.name)
        assertEquals("paprika-entry.json", canonical.sourceEntryName)
        assertEquals(1, canonical.ingredients.size)
        val ingredient = canonical.ingredients.single()
        assertEquals("2 tbsp unsalted butter, melted", ingredient.rawLine)
        assertEquals("unsalted butter", ingredient.parsedName)
        assertEquals(Rational.of(2), ingredient.quantity)
        assertEquals(UnitCode.TABLESPOON, ingredient.unit)
        assertEquals("melted", ingredient.preparation)
        assertEquals(butter, assertIs<IngredientResolution.Resolved>(ingredient.resolution).ingredient)
    }

    @Test
    fun keepsUnknownAndAmbiguousIngredientsReviewable() {
        val firstPepper = Ingredient(name = "Black pepper", aliases = setOf("pepper"))
        val secondPepper = Ingredient(name = "Sweet pepper", aliases = setOf("pepper"))
        val draft = RecipeDraft(
            sourceEntryName = "entry.json",
            externalId = null,
            name = "Seasoning",
            servings = null,
            ingredientLines = listOf("pepper", "sumac"),
            directionsText = "",
            notes = null,
            categories = emptyList(),
            sourceName = null,
            sourceUrl = null,
            photoFileName = null,
            photoHash = null,
        )
        val canonical = draft.toCanonicalRecipeDraft(IngredientResolver(listOf(firstPepper, secondPepper)))

        assertEquals("pepper", canonical.ingredients[0].rawLine)
        assertIs<IngredientResolution.NeedsReview>(canonical.ingredients[0].resolution)
        assertEquals("sumac", canonical.ingredients[1].rawLine)
        assertIs<IngredientResolution.Unresolved>(canonical.ingredients[1].resolution)
    }

    private fun recipeDraft(line: String): RecipeDraft = RecipeDraft(
        sourceEntryName = "paprika-entry.json",
        externalId = "source-id",
        name = "Quick sauce",
        servings = "2",
        ingredientLines = listOf(line),
        parsedIngredients = listOf(IngredientLineParser.parse(line)),
        directionsText = "Stir.",
        notes = null,
        categories = listOf("Sauce"),
        sourceName = "Example",
        sourceUrl = "https://example.test/recipe",
        photoFileName = null,
        photoHash = null,
    )
}
