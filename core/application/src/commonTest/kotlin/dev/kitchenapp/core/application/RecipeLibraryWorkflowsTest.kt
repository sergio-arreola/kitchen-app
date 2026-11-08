package dev.kitchenapp.core.application

import dev.kitchenapp.core.domain.ingredient.Ingredient
import dev.kitchenapp.core.domain.identifiers.RecipeId
import dev.kitchenapp.core.domain.recipe.Recipe
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RecipeLibraryWorkflowsTest {
    @Test
    fun `import preview uses current persisted ingredients and recipes`() {
        val ingredient = Ingredient(name = "Egg")
        val recipe = Recipe(name = "Egg toast", ingredients = emptyList(), directionsText = "Toast bread.")
        val kitchen = TestKitchenRepository(KitchenSnapshot(ingredients = listOf(ingredient)))
        val recipes = TestRecipeRepository(listOf(recipe))
        val gateway = RecordingRecipeImportGateway()

        val result = runSuspend {
            PreviewRecipeImport(gateway, recipes, kitchen).preview(RecipeImportRequest.Url("https://example.test"))
        }

        assertEquals(listOf(ingredient), gateway.ingredients)
        assertEquals(listOf(recipe), gateway.existingRecipes)
        assertEquals(listOf(gateway.candidate), result)
    }

    @Test
    fun `adding an existing canonical ingredient is idempotent`() {
        val existing = Ingredient(name = "All-purpose flour", aliases = setOf("plain flour"))
        val kitchen = TestKitchenRepository(KitchenSnapshot(ingredients = listOf(existing)))

        val resolved = AddCanonicalIngredient(kitchen).add("plain flour")

        assertSame(existing, resolved)
        assertEquals(listOf(existing), kitchen.snapshot.ingredients)
    }

    @Test
    fun `adding a new canonical ingredient persists it without stock`() {
        val kitchen = TestKitchenRepository()

        val added = AddCanonicalIngredient(kitchen).add("  Chickpeas  ")

        assertEquals("Chickpeas", added.name)
        assertEquals(listOf(added), kitchen.snapshot.ingredients)
        assertTrue(kitchen.snapshot.inventory.lots.isEmpty())
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

    private class RecordingRecipeImportGateway : RecipeImportGateway {
        lateinit var ingredients: List<Ingredient>
        lateinit var existingRecipes: List<Recipe>
        val candidate = RecipeImportCandidate(sourceKey = "source", outcome = RecipeImportOutcome.SUCCESS)

        override suspend fun preview(
            request: RecipeImportRequest,
            ingredients: List<Ingredient>,
            existingRecipes: List<Recipe>,
        ): List<RecipeImportCandidate> {
            this.ingredients = ingredients
            this.existingRecipes = existingRecipes
            return listOf(candidate)
        }
    }

    private class TestRecipeRepository(initial: List<Recipe> = emptyList()) : RecipeRepository {
        private val values = initial.associateByTo(linkedMapOf()) { it.id }

        override fun get(id: RecipeId): Recipe? = values[id]
        override fun getAll(): List<Recipe> = values.values.toList()
        override fun save(recipe: Recipe) { values[recipe.id] = recipe }
        override fun delete(id: RecipeId) { values.remove(id) }
    }

    private class TestKitchenRepository(
        var snapshot: KitchenSnapshot = KitchenSnapshot(),
    ) : KitchenRepository {
        override fun load(): KitchenSnapshot = snapshot
        override fun save(snapshot: KitchenSnapshot) { this.snapshot = snapshot }
    }
}
