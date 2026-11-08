package dev.kitchenapp.data.importers

import dev.kitchenapp.core.domain.ingredient.Ingredient
import dev.kitchenapp.core.domain.ingredient.IngredientResolution
import dev.kitchenapp.core.domain.ingredient.IngredientResolver
import dev.kitchenapp.core.domain.recipe.Recipe
import dev.kitchenapp.data.importers.paprika.ImportOutcome
import dev.kitchenapp.data.importers.paprika.toCanonicalRecipeDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RecipeImportAdaptersTest {
    @Test
    fun `imports JSON-LD recipe markup`() {
        val record = UrlRecipeImporter().import(
            page(
                body = """
                    <script type="application/ld+json">
                    {
                      "@context":"https://schema.org",
                      "@type":"Recipe",
                      "name":"Bean soup",
                      "recipeYield":"4 servings",
                      "recipeIngredient":["1 cup beans","2 cups water"],
                      "recipeInstructions":[{"@type":"HowToStep","text":"Simmer."}],
                      "recipeCategory":["Dinner"],
                      "description":"A simple soup."
                    }
                    </script>
                """.trimIndent(),
            ),
        ).single()

        val success = assertIs<UrlRecipeImportRecord.Success>(record)
        assertEquals("Bean soup", success.draft.name)
        assertEquals(listOf("1 cup beans", "2 cups water"), success.draft.ingredientLines)
        assertEquals("Simmer.", success.draft.directionsText)
        assertEquals("4 servings", success.draft.servings)
        assertEquals("https://recipes.test/bean-soup", success.draft.sourceUrl)
    }

    @Test
    fun `normalizes yield and captures recipe times and image URL`() {
        val record = UrlRecipeImporter().import(
            page(
                body = """
                    <script type="application/ld+json">
                    {
                      "@type":"Recipe",
                      "name":"Homemade Cranberry Sauce",
                      "recipeYield":["6","6 servings"],
                      "prepTime":"PT5M",
                      "cookTime":"PT15M",
                      "totalTime":"PT20M",
                      "image":{"@type":"ImageObject","url":"https://images.example.test/cranberry.jpg"},
                      "recipeIngredient":["1 cup cranberries"],
                      "recipeInstructions":"Simmer."
                    }
                    </script>
                    <span>Chilling Time: 2 hrs</span>
                """.trimIndent(),
            ),
        ).single()

        val success = assertIs<UrlRecipeImportRecord.Success>(record)
        assertEquals("6 servings", success.draft.servings)
        assertEquals(300L, success.draft.prepTimeSeconds)
        assertEquals(900L, success.draft.cookTimeSeconds)
        assertEquals(7_200L, success.draft.chillingTimeSeconds)
        assertEquals(1_200L, success.draft.totalTimeSeconds)
        assertEquals("https://images.example.test/cranberry.jpg", success.draft.imageSourceUrl)
    }

    @Test
    fun `keeps incomplete structured recipe as a warned draft`() {
        val record = UrlRecipeImporter().import(
            page(body = """<script type="application/ld+json">{"@type":"Recipe","name":"Plain bread"}</script>"""),
        ).single()

        val warned = assertIs<UrlRecipeImportRecord.SuccessWithWarnings>(record)
        assertEquals(setOf("missing ingredients", "missing directions"), warned.warnings.toSet())
        assertEquals(ImportOutcome.SUCCESS_WITH_WARNINGS, warned.outcome)
    }

    @Test
    fun `falls back from malformed JSON-LD to supported Recipe microdata`() {
        val record = UrlRecipeImporter().import(
            page(
                body = """
                    <script type="application/ld+json">{broken}</script>
                    <div itemscope itemtype="https://schema.org/Recipe">
                      <meta itemprop="name" content="Microwave oats">
                      <span itemprop="recipeIngredient">1 cup oats</span>
                      <div itemprop="recipeInstructions">Add water and heat.</div>
                    </div>
                """.trimIndent(),
            ),
        ).single()

        val warned = assertIs<UrlRecipeImportRecord.SuccessWithWarnings>(record)
        assertEquals("Microwave oats", warned.draft.name)
        assertEquals(listOf("1 cup oats"), warned.draft.ingredientLines)
        assertEquals("Malformed JSON-LD was ignored; imported Schema.org microdata instead.", warned.warnings.single())
    }

    @Test
    fun `malformed and unsupported pages remain available for manual review`() {
        val malformed = UrlRecipeImporter().import(
            page(body = """<script type="application/ld+json">{broken}</script>"""),
        ).single()
        val unsupported = UrlRecipeImporter().import(page(body = "<html><title>Family pie</title></html>")).single()

        val malformedReview = assertIs<UrlRecipeImportRecord.NeedsManualReview>(malformed)
        assertEquals(ImportOutcome.NEEDS_REVIEW, malformedReview.outcome)
        val unsupportedReview = assertIs<UrlRecipeImportRecord.NeedsManualReview>(unsupported)
        assertEquals("Family pie", unsupportedReview.suggestedName)
    }

    @Test
    fun `ambiguous recipe pages require review`() {
        val record = UrlRecipeImporter().import(
            page(
                body = """
                    <script type="application/ld+json">
                    [{"@type":"Recipe","name":"One"},{"@type":"Recipe","name":"Two"}]
                    </script>
                """.trimIndent(),
            ),
        ).single()

        assertIs<UrlRecipeImportRecord.NeedsManualReview>(record)

        val identicalRecords = UrlRecipeImporter().import(
            page(
                body = """
                    <script type="application/ld+json">
                    [{"@type":"Recipe","name":"One"},{"@type":"Recipe","name":"One"}]
                    </script>
                """.trimIndent(),
            ),
        ).single()
        assertIs<UrlRecipeImportRecord.NeedsManualReview>(identicalRecords)
    }

    @Test
    fun `manual entry flows through canonical ingredient review`() {
        val butter = Ingredient(name = "Butter", aliases = setOf("unsalted butter"))
        val record = ManualRecipeImporter().import(
            ManualRecipeInput(
                sourceKey = "manual-1",
                name = "Quick sauce",
                ingredientLines = listOf("2 tbsp unsalted butter, melted"),
                directionsText = "Stir.",
            ),
        ).single()

        val canonical = assertNotNull(record.draft).toCanonicalRecipeDraft(IngredientResolver(listOf(butter)))
        assertEquals("Quick sauce", canonical.name)
        assertEquals("2 tbsp unsalted butter, melted", canonical.ingredients.single().rawLine)
        assertEquals(butter, assertIs<IngredientResolution.Resolved>(canonical.ingredients.single().resolution).ingredient)
    }

    @Test
    fun `preview flags existing and in-batch duplicates without dropping failures`() {
        val importer = UrlRecipeImporter()
        val first = importer.import(
            page(
                url = "https://recipes.test/one",
                finalUrl = "https://recipes.test/one",
                body = """<script type="application/ld+json">{"@type":"Recipe","name":"Bean soup"}</script>""",
            ),
        ).single()
        val second = importer.import(
            page(
                url = "https://recipes.test/two",
                finalUrl = "https://recipes.test/two",
                body = """<script type="application/ld+json">{"@type":"Recipe","name":"Bean soup"}</script>""",
            ),
        ).single()
        val known = importer.import(
            page(
                url = "https://recipes.test/three",
                finalUrl = "https://recipes.test/three",
                body = """<script type="application/ld+json">{"@type":"Recipe","name":"Known recipe"}</script>""",
            ),
        ).single()
        val failed = ManualRecipeImporter().import(ManualRecipeInput(sourceKey = "bad", name = " ")).single()
        val existing = Recipe(
            name = "Known recipe",
            ingredients = emptyList(),
            directionsText = "",
            sourceUrl = "https://recipes.test/three",
        )

        val previews = RecipeImportPreviewer().preview(listOf(first, second, known, failed), listOf(existing))

        assertNull(previews[0].duplicateOf)
        assertEquals(first.sourceKey, previews[1].duplicateOf)
        assertEquals(existing.id.toString(), previews[2].duplicateOf)
        assertEquals(4, previews.size)
        assertEquals(ImportOutcome.FAILED, previews[3].record.outcome)
        assertNull(previews[3].record.draft)
    }

    private fun page(
        url: String = "https://recipes.test/bean-soup",
        finalUrl: String = "https://recipes.test/bean-soup",
        body: String,
    ) = dev.kitchenapp.core.application.RecipePage(
        requestedUrl = url,
        finalUrl = finalUrl,
        statusCode = 200,
        contentType = "text/html",
        body = body,
    )
}
