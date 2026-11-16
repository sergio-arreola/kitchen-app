package dev.kitchenapp.core.application

import dev.kitchenapp.core.domain.ingredient.Ingredient
import dev.kitchenapp.core.domain.identifiers.RecipeId
import dev.kitchenapp.core.domain.recipe.Recipe
import dev.kitchenapp.core.domain.time.DomainInstant
import kotlin.time.Clock
import kotlin.time.Instant
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
    fun `library filters categories and sorts newest imports first`() {
        val newest = Recipe(
            name = "Newest",
            ingredients = emptyList(),
            directionsText = "",
            categories = listOf("Dinner"),
            importedAt = DomainInstant(Instant.parse("2026-11-08T12:00:00Z")),
        )
        val older = Recipe(
            name = "Older",
            ingredients = emptyList(),
            directionsText = "",
            categories = listOf("Dinner"),
            importedAt = DomainInstant(Instant.parse("2026-11-07T12:00:00Z")),
        )
        val legacy = Recipe(name = "Legacy", ingredients = emptyList(), directionsText = "")
        val recipes = TestRecipeRepository(listOf(older, legacy, newest))
        val search = SearchRecipes(recipes)

        assertEquals(listOf(newest, older, legacy), search.search("", RecipeLibraryFilter.MostRecent))
        assertEquals(listOf(legacy), search.search("", RecipeLibraryFilter.Uncategorized))
        assertEquals(setOf(newest, older), search.search("", RecipeLibraryFilter.Category("dinner")).toSet())
    }

    @Test
    fun `most recent prioritizes import time over alphabetic recipe names`() {
        val newest = Recipe(
            name = "Zucchini supper",
            ingredients = emptyList(),
            directionsText = "Cook.",
            importedAt = DomainInstant(Instant.parse("2026-11-09T12:00:00Z")),
        )
        val older = newest.copy(
            id = RecipeId.generate(),
            name = "Apple breakfast",
            importedAt = DomainInstant(Instant.parse("2026-11-08T12:00:00Z")),
        )
        val legacyBanana = Recipe(name = "Banana legacy", ingredients = emptyList(), directionsText = "")
        val legacyApricot = Recipe(name = "Apricot legacy", ingredients = emptyList(), directionsText = "")
        val search = SearchRecipes(TestRecipeRepository(listOf(legacyBanana, older, legacyApricot, newest)))

        assertEquals(
            listOf(newest, older, legacyApricot, legacyBanana),
            search.search("", RecipeLibraryFilter.MostRecent),
        )
    }

    @Test
    fun `review save normalizes servings and sets import time only once`() {
        val now = Instant.parse("2026-11-08T12:00:00Z")
        val recipes = TestRecipeRepository()
        val save = SaveReviewedRecipe(recipes, TestKitchenRepository(), object : Clock {
            override fun now(): Instant = now
        })
        val imported = save.save(
            Recipe(
                name = "Cranberry sauce",
                servings = "6, 6 servings",
                ingredients = emptyList(),
                directionsText = "Simmer.",
                categories = listOf(" Holiday ", "holiday"),
            ),
        )

        assertEquals("6 servings", imported.servings)
        assertEquals(listOf("Holiday"), imported.categories)
        assertEquals(DomainInstant(now), imported.importedAt)

        val edited = save.save(imported.copy(name = "Holiday cranberry sauce"))
        assertEquals(DomainInstant(now), edited.importedAt)

        val legacy = Recipe(name = "Legacy", ingredients = emptyList(), directionsText = "")
        recipes.save(legacy)
        assertEquals(null, save.save(legacy.copy(name = "Edited legacy")).importedAt)
    }

    @Test
    fun `category operations and recipe deletion preserve recipe data`() {
        val recipe = Recipe(
            name = "Bean soup",
            ingredients = emptyList(),
            directionsText = "Simmer.",
            categories = listOf("Dinner"),
        )
        val recipes = TestRecipeRepository(listOf(recipe))

        CreateRecipeCategory(recipes).create("Holiday")
        RenameRecipeCategory(recipes).rename("Dinner", "Supper")
        assertEquals(listOf("Holiday", "Supper"), ListRecipeCategories(recipes).getAll())

        RemoveRecipeCategory(recipes).remove("Supper")
        assertEquals(emptyList(), recipes.get(recipe.id)?.categories)
        assertEquals(false, DeleteRecipe(recipes).delete(RecipeId.generate()))
        assertEquals(true, DeleteRecipe(recipes).delete(recipe.id))
        assertEquals(null, recipes.get(recipe.id))
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

    private class TestRecipeRepository(initial: List<Recipe> = emptyList()) : RecipeRepository, RecipeImageRepository {
        private val values = initial.associateByTo(linkedMapOf()) { it.id }
        private val categoryNames = initial.flatMap { it.categories }.toMutableSet()
        private val images = mutableMapOf<RecipeId, dev.kitchenapp.core.domain.recipe.RecipeImage>()

        override fun get(id: RecipeId): Recipe? = values[id]
        override fun getAll(): List<Recipe> = values.values.toList()
        override fun save(recipe: Recipe) {
            values[recipe.id] = recipe
            categoryNames += recipe.categories
        }
        override fun delete(id: RecipeId) {
            values.remove(id)
            images.remove(id)
        }
        override fun getRecipeImage(recipeId: RecipeId) = images[recipeId]
        override fun saveWithImage(recipe: Recipe, image: dev.kitchenapp.core.domain.recipe.RecipeImage?) {
            save(recipe)
            image?.let { images[recipe.id] = it }
        }
        override fun getCategories(): List<String> = categoryNames.toList()
        override fun createCategory(name: String) { categoryNames += name }
        override fun renameCategory(oldName: String, newName: String) {
            values.replaceAll { _, recipe ->
                recipe.copy(categories = recipe.categories.map {
                    if (it.equals(oldName, ignoreCase = true)) newName else it
                }.distinctBy(String::lowercase))
            }
            categoryNames.removeAll { it.equals(oldName, ignoreCase = true) }
            categoryNames += newName
        }
        override fun removeCategory(name: String) {
            values.replaceAll { _, recipe ->
                recipe.copy(categories = recipe.categories.filterNot { it.equals(name, ignoreCase = true) })
            }
            categoryNames.removeAll { it.equals(name, ignoreCase = true) }
        }
    }

    private class TestKitchenRepository(
        var snapshot: KitchenSnapshot = KitchenSnapshot(),
    ) : KitchenRepository {
        override fun load(): KitchenSnapshot = snapshot
        override fun save(snapshot: KitchenSnapshot) { this.snapshot = snapshot }
    }
}
