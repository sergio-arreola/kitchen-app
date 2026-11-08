package dev.kitchenapp.data.importers

import dev.kitchenapp.core.application.RecipeImportCandidate
import dev.kitchenapp.core.application.RecipeImportGateway
import dev.kitchenapp.core.application.RecipeImportOutcome
import dev.kitchenapp.core.application.RecipeImportImageSource
import dev.kitchenapp.core.application.RecipeImportRequest
import dev.kitchenapp.core.application.RecipePageFetcher
import dev.kitchenapp.core.domain.identifiers.RecipeId
import dev.kitchenapp.core.domain.ingredient.Ingredient
import dev.kitchenapp.core.domain.ingredient.IngredientResolver
import dev.kitchenapp.core.domain.recipe.Recipe
import kotlin.coroutines.cancellation.CancellationException
import dev.kitchenapp.data.importers.archive.ArchiveReader
import dev.kitchenapp.data.importers.archive.ImportSource
import dev.kitchenapp.data.importers.paprika.ImportOutcome
import dev.kitchenapp.data.importers.paprika.PaprikaImportRecord
import dev.kitchenapp.data.importers.paprika.PaprikaRecipeImporter
import dev.kitchenapp.data.importers.paprika.toCanonicalRecipeDraft

/** Connects existing source adapters to the application import preview boundary. */
class DefaultRecipeImportGateway(
    private val archiveReader: ArchiveReader,
    pageFetcher: RecipePageFetcher,
    private val manualImporter: ManualRecipeImporter = ManualRecipeImporter(),
    private val urlImporter: UrlRecipeImportService = UrlRecipeImportService(pageFetcher),
) : RecipeImportGateway {
    override suspend fun preview(
        request: RecipeImportRequest,
        ingredients: List<Ingredient>,
        existingRecipes: List<Recipe>,
    ): List<RecipeImportCandidate> {
        val records = try {
            when (request) {
                is RecipeImportRequest.PaprikaArchive -> PaprikaRecipeImporter(archiveReader)
                    .import(ImportSource(request.source))
                is RecipeImportRequest.Url -> listOf(urlImporter.import(request.url))
                is RecipeImportRequest.Manual -> manualImporter.import(
                    ManualRecipeInput(
                        name = request.name,
                        servings = request.servings,
                        ingredientLines = request.ingredientLines,
                        directionsText = request.directionsText,
                        notes = request.notes,
                        categories = request.categories,
                        sourceUrl = request.sourceUrl,
                        sourceKey = "manual:${RecipeId.generate()}",
                    ),
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            return listOf(
                RecipeImportCandidate(
                    sourceKey = request.sourceKey(),
                    outcome = RecipeImportOutcome.FAILED,
                    reason = "Import failed (${failure::class.simpleName}).",
                ),
            )
        }

        val previews = RecipeImportPreviewer().preview(records, existingRecipes)
        val resolver = IngredientResolver(ingredients)
        return previews.map { preview ->
            val record = preview.record
            RecipeImportCandidate(
                sourceKey = record.sourceKey,
                outcome = record.outcome.toApplicationOutcome(),
                draft = record.draft?.toCanonicalRecipeDraft(resolver),
                warnings = record.warnings,
                reason = record.reasonOrNull(),
                suggestedName = (record as? UrlRecipeImportRecord.NeedsManualReview)?.suggestedName,
                duplicateOf = preview.duplicateOf,
                imageSource = when (request) {
                    is RecipeImportRequest.Url ->
                        record.draft?.imageSourceUrl?.let(RecipeImportImageSource::Url)
                    is RecipeImportRequest.PaprikaArchive ->
                        record.draft?.photoFileName?.let { fileName ->
                            val entryName = if (fileName.startsWith("photos/")) fileName else "photos/$fileName"
                            RecipeImportImageSource.PaprikaArchiveEntry(request.source, entryName)
                        }
                    is RecipeImportRequest.Manual -> null
                },
            )
        }
    }

    private fun RecipeImportRequest.sourceKey(): String = when (this) {
        is RecipeImportRequest.PaprikaArchive -> source
        is RecipeImportRequest.Url -> url
        is RecipeImportRequest.Manual -> "manual"
    }

    private fun ImportOutcome.toApplicationOutcome(): RecipeImportOutcome = when (this) {
        ImportOutcome.SUCCESS -> RecipeImportOutcome.SUCCESS
        ImportOutcome.SUCCESS_WITH_WARNINGS -> RecipeImportOutcome.SUCCESS_WITH_WARNINGS
        ImportOutcome.NEEDS_REVIEW -> RecipeImportOutcome.NEEDS_REVIEW
        ImportOutcome.FAILED -> RecipeImportOutcome.FAILED
        ImportOutcome.SKIPPED -> RecipeImportOutcome.SKIPPED
    }

    private fun RecipeImportRecord.reasonOrNull(): String? = when (this) {
        is PaprikaImportRecord.Failure -> reason
        is PaprikaImportRecord.Skipped -> reason
        is UrlRecipeImportRecord.NeedsManualReview -> reason
        is UrlRecipeImportRecord.Failure -> reason
        is ManualRecipeImportRecord.Failure -> reason
        else -> null
    }
}
