package dev.kitchenapp.data.importers

import dev.kitchenapp.core.application.RecipeImportOutcome
import dev.kitchenapp.core.application.RecipeImportImageSource
import dev.kitchenapp.core.application.RecipeImportRequest
import dev.kitchenapp.core.application.RecipePage
import dev.kitchenapp.core.application.RecipePageFetchResult
import dev.kitchenapp.core.application.RecipePageFetcher
import dev.kitchenapp.core.domain.ingredient.Ingredient
import dev.kitchenapp.core.domain.ingredient.IngredientResolution
import dev.kitchenapp.core.domain.recipe.Recipe
import dev.kitchenapp.data.importers.archive.ArchiveEntrySummary
import dev.kitchenapp.data.importers.archive.ArchiveReader
import dev.kitchenapp.data.importers.archive.GzipOutcome
import dev.kitchenapp.data.importers.archive.ImportSource
import dev.kitchenapp.data.importers.archive.PaprikaArchiveInspection
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class DefaultRecipeImportGatewayTest {
    @Test
    fun `manual preview maps importer draft to resolved canonical review record`() {
        val egg = Ingredient(name = "Egg")
        val gateway = DefaultRecipeImportGateway(EmptyArchiveReader, UnusedPageFetcher)

        val result = runSuspend {
            gateway.preview(
                RecipeImportRequest.Manual(
                    name = "Egg toast",
                    ingredientLines = listOf("1 egg"),
                    directionsText = "Cook the egg.",
                ),
                ingredients = listOf(egg),
                existingRecipes = emptyList(),
            )
        }.single()

        assertEquals(RecipeImportOutcome.SUCCESS, result.outcome)
        assertEquals("Egg toast", result.draft?.name)
        assertEquals(egg, assertIs<IngredientResolution.Resolved>(result.draft?.ingredients?.single()?.resolution).ingredient)
    }

    @Test
    fun `manual validation failure remains visible as a record`() {
        val result = runSuspend {
            DefaultRecipeImportGateway(EmptyArchiveReader, UnusedPageFetcher).preview(
                RecipeImportRequest.Manual(name = " "),
                ingredients = emptyList(),
                existingRecipes = emptyList(),
            )
        }.single()

        assertEquals(RecipeImportOutcome.FAILED, result.outcome)
        assertNotNull(result.reason)
        kotlin.test.assertNull(result.draft)
    }

    @Test
    fun `url preview flags an existing source URL as a duplicate`() {
        val page = RecipePage(
            requestedUrl = "https://example.test/toast",
            finalUrl = "https://example.test/toast",
            statusCode = 200,
            contentType = "application/ld+json",
            body = """{"@context":"https://schema.org","@type":"Recipe","name":"Toast","recipeIngredient":["1 slice bread"],"recipeInstructions":"Toast bread.","image":"https://images.example.test/toast.jpg"}""",
        )
        val existing = Recipe(
            name = "Toast",
            ingredients = emptyList(),
            directionsText = "Toast bread.",
            sourceUrl = page.finalUrl,
        )
        val gateway = DefaultRecipeImportGateway(EmptyArchiveReader, FixedPageFetcher(page))

        val result = runSuspend {
            gateway.preview(
                RecipeImportRequest.Url(page.requestedUrl),
                ingredients = emptyList(),
                existingRecipes = listOf(existing),
            )
        }.single()

        assertEquals(existing.id.toString(), result.duplicateOf)
        assertEquals(RecipeImportOutcome.SUCCESS, result.outcome)
        assertEquals(RecipeImportImageSource.Url("https://images.example.test/toast.jpg"), result.imageSource)
    }

    @Test
    fun `Paprika photo reference becomes a review image source`() {
        val archiveReader = object : ArchiveReader {
            override fun inspect(source: ImportSource) = PaprikaArchiveInspection(
                source = source,
                fileCount = 2,
                entries = listOf(
                    ArchiveEntrySummary("recipes/photo.json", 1, 1, false, GzipOutcome.SUCCESS),
                    ArchiveEntrySummary("photos/source.jpg", 1, 1, false, GzipOutcome.FAILED),
                ),
            )

            override fun readEntryContent(source: ImportSource, entryName: String): ByteArray? =
                if (entryName == "recipes/photo.json") {
                    """{"name":"Photo bread","photo":"source.jpg","ingredients":"1 egg","directions":"Cook."}"""
                        .encodeToByteArray()
                } else {
                    null
                }
        }
        val gateway = DefaultRecipeImportGateway(archiveReader, UnusedPageFetcher)

        val result = runSuspend {
            gateway.preview(
                RecipeImportRequest.PaprikaArchive("fixture.paprikarecipes"),
                ingredients = emptyList(),
                existingRecipes = emptyList(),
            )
        }

        assertEquals(
            RecipeImportImageSource.PaprikaArchiveEntry("fixture.paprikarecipes", "photos/source.jpg"),
            result.first().imageSource,
        )
    }

    private object EmptyArchiveReader : ArchiveReader {
        override fun inspect(source: ImportSource) = PaprikaArchiveInspection(source, 0, emptyList())
        override fun readEntryContent(source: ImportSource, entryName: String): ByteArray? = null
    }

    private object UnusedPageFetcher : RecipePageFetcher {
        override suspend fun fetch(url: String): RecipePageFetchResult =
            RecipePageFetchResult.Failure(url, "unused")
    }

    private class FixedPageFetcher(private val page: RecipePage) : RecipePageFetcher {
        override suspend fun fetch(url: String): RecipePageFetchResult = RecipePageFetchResult.Success(page)
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var completion: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) {
                completion = result
            }
        })
        return checkNotNull(completion).getOrThrow()
    }
}
