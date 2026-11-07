package dev.kitchenapp.data.importers

import dev.kitchenapp.core.application.RecipePageFetchResult
import dev.kitchenapp.core.application.RecipePageFetcher

/** Fetches a user URL and preserves transport failures as visible import records. */
class UrlRecipeImportService(
    private val fetcher: RecipePageFetcher,
    private val importer: UrlRecipeImporter = UrlRecipeImporter(),
) {
    suspend fun import(url: String): UrlRecipeImportRecord = when (val result = fetcher.fetch(url)) {
        is RecipePageFetchResult.Success -> importer.import(result.page).single()
        is RecipePageFetchResult.Failure -> UrlRecipeImportRecord.Failure(
            sourceKey = result.requestedUrl,
            reason = result.reason,
        )
    }
}
