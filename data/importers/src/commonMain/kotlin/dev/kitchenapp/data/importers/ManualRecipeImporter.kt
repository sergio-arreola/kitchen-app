package dev.kitchenapp.data.importers

import dev.kitchenapp.data.importers.ingredient.IngredientLineParser
import dev.kitchenapp.data.importers.paprika.ImportOutcome
import dev.kitchenapp.data.importers.paprika.RecipeDraft

data class ManualRecipeInput(
    val name: String,
    val servings: String? = null,
    val ingredientLines: List<String> = emptyList(),
    val directionsText: String = "",
    val notes: String? = null,
    val categories: List<String> = emptyList(),
    val sourceUrl: String? = null,
    val sourceKey: String = "manual",
)

sealed interface ManualRecipeImportRecord : RecipeImportRecord {
    data class Success(
        override val sourceKey: String,
        override val draft: RecipeDraft,
    ) : ManualRecipeImportRecord {
        override val outcome = ImportOutcome.SUCCESS
    }

    data class SuccessWithWarnings(
        override val sourceKey: String,
        override val draft: RecipeDraft,
        override val warnings: List<String>,
    ) : ManualRecipeImportRecord {
        override val outcome = ImportOutcome.SUCCESS_WITH_WARNINGS
    }

    data class Failure(
        override val sourceKey: String,
        val reason: String,
    ) : ManualRecipeImportRecord {
        override val outcome = ImportOutcome.FAILED
        override val draft: RecipeDraft? = null
    }
}

/** Creates a normal importer draft so manual input follows the same canonical review path. */
class ManualRecipeImporter : RecipeImporter<ManualRecipeInput, ManualRecipeImportRecord> {
    override fun import(source: ManualRecipeInput): List<ManualRecipeImportRecord> {
        val name = source.name.trim()
        if (name.isEmpty()) {
            return listOf(ManualRecipeImportRecord.Failure(source.sourceKey, "missing name"))
        }
        val lines = source.ingredientLines.filter(String::isNotBlank)
        val warnings = buildList {
            if (lines.isEmpty()) add("missing ingredients")
            if (source.directionsText.isBlank()) add("missing directions")
        }
        val draft = RecipeDraft(
            sourceEntryName = source.sourceKey,
            externalId = null,
            name = name,
            servings = source.servings?.trim()?.ifBlank { null },
            ingredientLines = lines,
            parsedIngredients = lines.map(IngredientLineParser::parse),
            directionsText = source.directionsText.trim(),
            notes = source.notes?.trim()?.ifBlank { null },
            categories = source.categories.map(String::trim).filter(String::isNotBlank).distinct(),
            sourceName = "Manual entry",
            sourceUrl = source.sourceUrl?.trim()?.ifBlank { null },
            photoFileName = null,
            photoHash = null,
        )
        return if (warnings.isEmpty()) {
            listOf(ManualRecipeImportRecord.Success(source.sourceKey, draft))
        } else {
            listOf(ManualRecipeImportRecord.SuccessWithWarnings(source.sourceKey, draft, warnings))
        }
    }
}
