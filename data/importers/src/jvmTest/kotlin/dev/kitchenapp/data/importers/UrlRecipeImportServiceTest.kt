package dev.kitchenapp.data.importers

import dev.kitchenapp.core.application.RecipePageFetchResult
import dev.kitchenapp.core.application.RecipePageFetcher
import dev.kitchenapp.data.importers.paprika.ImportOutcome
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class UrlRecipeImportServiceTest {
    @Test
    fun `network failures remain visible as failed import records`() = runBlocking {
        val service = UrlRecipeImportService(
            fetcher = RecipePageFetcher { url ->
                RecipePageFetchResult.Failure(url, "connection failed")
            },
        )

        val record = service.import("https://recipes.test/offline")

        val failure = assertIs<UrlRecipeImportRecord.Failure>(record)
        assertEquals("https://recipes.test/offline", failure.sourceKey)
        assertEquals("connection failed", failure.reason)
        assertEquals(ImportOutcome.FAILED, failure.outcome)
    }
}
