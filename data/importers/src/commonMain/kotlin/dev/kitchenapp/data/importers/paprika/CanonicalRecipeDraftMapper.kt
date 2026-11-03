package dev.kitchenapp.data.importers.paprika

import dev.kitchenapp.core.domain.ingredient.IngredientResolver
import dev.kitchenapp.core.domain.recipe.CanonicalRecipeDraft
import dev.kitchenapp.core.domain.recipe.CanonicalRecipeIngredient
import dev.kitchenapp.data.importers.ingredient.IngredientLineParser

/** Maps an importer draft to a source-independent, reviewable domain draft. */
public fun RecipeDraft.toCanonicalRecipeDraft(
    resolver: IngredientResolver,
): CanonicalRecipeDraft {
    val parsed = if (parsedIngredients.size == ingredientLines.size) {
        parsedIngredients
    } else {
        ingredientLines.map(IngredientLineParser::parse)
    }

    return CanonicalRecipeDraft(
        name = name,
        servings = servings,
        ingredients = ingredientLines.mapIndexed { index, rawLine ->
            val parsedIngredient = parsed[index]
            CanonicalRecipeIngredient(
                rawLine = rawLine,
                parsedName = parsedIngredient.name,
                quantity = parsedIngredient.quantity,
                quantityHigh = parsedIngredient.quantityHigh,
                unit = parsedIngredient.unit,
                preparation = parsedIngredient.preparation,
                importance = parsedIngredient.importance,
                approximateLevel = parsedIngredient.approximateLevel,
                resolution = resolver.resolve(parsedIngredient.name),
            )
        },
        directionsText = directionsText,
        notes = notes,
        categories = categories,
        sourceEntryName = sourceEntryName,
        externalId = externalId,
        sourceName = sourceName,
        sourceUrl = sourceUrl,
    )
}
