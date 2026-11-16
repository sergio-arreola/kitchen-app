package dev.kitchenapp.data.importers

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.kitchenapp.core.application.KitchenSnapshot
import dev.kitchenapp.core.application.RecipeImportCandidate
import dev.kitchenapp.core.application.RecipeImportImageSource
import dev.kitchenapp.core.application.RecipeImportOutcome
import dev.kitchenapp.core.application.RecipeImportRequest
import dev.kitchenapp.core.application.RecipePage
import dev.kitchenapp.core.application.RecipePageFetchResult
import dev.kitchenapp.core.application.RecipePageFetcher
import dev.kitchenapp.core.application.SaveReviewedRecipe
import dev.kitchenapp.core.domain.enums.ApproximateLevel
import dev.kitchenapp.core.domain.enums.IngredientImportance
import dev.kitchenapp.core.domain.identifiers.IngredientId
import dev.kitchenapp.core.domain.ingredient.Ingredient
import dev.kitchenapp.core.domain.ingredient.IngredientResolution
import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.core.domain.recipe.CanonicalRecipeDraft
import dev.kitchenapp.core.domain.recipe.Recipe
import dev.kitchenapp.core.domain.recipe.RecipeImage
import dev.kitchenapp.core.domain.recipe.RecipeIngredient
import dev.kitchenapp.core.domain.units.UnitCode
import dev.kitchenapp.data.database.generated.KitchenDatabase
import dev.kitchenapp.data.database.SqlDelightKitchenRepository
import dev.kitchenapp.data.database.SqlDelightRecipeRepository
import dev.kitchenapp.data.importers.archive.ImportSource
import dev.kitchenapp.data.importers.archive.PaprikaFixture
import dev.kitchenapp.data.importers.archive.ZipFileArchiveReader
import dev.kitchenapp.data.importers.paprika.ImportOutcome
import dev.kitchenapp.data.importers.paprika.PaprikaRecipeImporter
import java.io.ByteArrayInputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class Slice13ImportAcceptanceTest {
    @Test
    fun `reviewed Paprika corpus accounts for valid malformed and non recipe entries`() {
        val directory = Files.createTempDirectory("slice13-paprika-corpus").toFile()
        try {
            val reviewedRecords = reviewedPaprikaRecords()
            val archive = PaprikaFixture.buildZip(
                directory,
                extraRecipeJson = reviewedRecords + mapOf(
                    "recipes/missing-name.json" to """{"uid":"invalid-name","ingredients":"water"}""",
                    "recipes/malformed.json" to "{broken}",
                ),
            )
            val records = PaprikaRecipeImporter(ZipFileArchiveReader()).import(ImportSource(archive.path))
            val validIds = PaprikaFixture.recipeEntries.map { "recipes/$it.json" }.toSet() + reviewedRecords.keys
            assertEquals(40, validIds.size)
            val valid = records.filter { it.sourceKey in validIds }
            val successful = valid.filter {
                it.outcome == ImportOutcome.SUCCESS || it.outcome == ImportOutcome.SUCCESS_WITH_WARNINGS
            }
            val failedIds = valid.filterNot { it in successful }.map { it.sourceKey }
            val warningIds = valid.filter { it.warnings.isNotEmpty() }.map { it.sourceKey }
            val malformedIds = records.filter { it.outcome == ImportOutcome.FAILED }.map { it.sourceKey }.toSet()
            val skippedIds = records.filter { it.outcome == ImportOutcome.SKIPPED }.map { it.sourceKey }.toSet()

            println("$CORPUS_REVISION Paprika success=${successful.size}/${validIds.size}; warningCount=${valid.sumOf { it.warnings.size }}; warningIds=$warningIds; failed=$failedIds; malformed=$malformedIds; skipped=$skippedIds")
            assertEquals(validIds.size, valid.size)
            assertTrue(successful.size.toDouble() / validIds.size >= 0.95, "Failed valid case IDs: $failedIds")
            assertTrue(failedIds.isEmpty(), "Reviewed valid cases changed: $failedIds")
            assertEquals(
                setOf("recipes/warned-no-uid.json", "recipes/warned-no-ingredients.json", "recipes/warned-no-directions.json"),
                warningIds.toSet(),
            )
            assertEquals(setOf("recipes/missing-name.json", "recipes/malformed.json"), malformedIds)
            assertEquals(setOf("recipes/", "photos/fixture-0002.jpeg"), skippedIds)
            assertEquals(validIds.size + malformedIds.size + skippedIds.size, records.size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `reviewed ingredient corpus survives every adapter review save and database reopen`() = runBlocking(Dispatchers.IO) {
        val directory = Files.createTempDirectory("slice13-import-pipeline").toFile()
        try {
            val archive = PaprikaFixture.buildZip(
                directory,
                extraRecipeJson = mapOf("recipes/normalization-corpus.json" to paprikaJson()),
            )
            val gateway = DefaultRecipeImportGateway(ZipFileArchiveReader(), CorpusPageFetcher)
            val requests = listOf(
                "paprika" to RecipeImportRequest.PaprikaArchive(archive.path),
                "url" to RecipeImportRequest.Url(SOURCE_URL),
                "manual" to RecipeImportRequest.Manual(
                    name = RECIPE_NAME,
                    servings = "4 servings",
                    ingredientLines = cases.map { it.line },
                    directionsText = DIRECTIONS,
                    categories = listOf("Acceptance"),
                    sourceUrl = SOURCE_URL,
                ),
            )
            assertEquals(26, cases.size)
            assertEquals(cases.size, cases.map { it.id }.distinct().size)

            requests.forEach { (source, request) ->
                val candidate = gateway.preview(request, catalog, emptyList()).single { it.draft?.name == RECIPE_NAME }
                val draft = assertNotNull(candidate.draft)
                assertEquals(RecipeImportOutcome.SUCCESS, candidate.outcome, source)
                assertEquals("4 servings", draft.servings, source)
                assertEquals(listOf("Acceptance"), draft.categories, source)
                assertEquals(SOURCE_URL, draft.sourceUrl, source)
                assertEquals(if (source == "url") 300L else null, draft.prepTimeSeconds, source)
                assertEquals(if (source == "url") 600L else null, draft.cookTimeSeconds, source)
                assertEquals(if (source == "url") 900L else null, draft.totalTimeSeconds, source)
                assertEquals(null, draft.chillingTimeSeconds, source)
                assertEquals(cases.size, draft.ingredients.size, source)
                val failures = cases.mapIndexedNotNull { index, case ->
                    val actual = draft.ingredients[index]
                    val correct = resolutionOutcome(actual.resolution) == case.expected &&
                        actual.rawLine == case.line && actual.parsedName == case.parsedName &&
                        actual.quantity == case.quantity && actual.quantityHigh == case.quantityHigh &&
                        actual.unit == case.unit && actual.preparation == case.preparation &&
                        actual.importance == case.importance && actual.approximateLevel == case.approximateLevel
                    if (correct) null else "$source/${case.id}: expected=$case, actual=$actual"
                }
                val resolved = draft.ingredients.count { it.resolution is IngredientResolution.Resolved }
                val correct = cases.size - failures.size
                println("$CORPUS_REVISION $source normalization=$correct/${cases.size}; resolved=$resolved/${cases.size}; failedCaseIds=${failures.map { it.substringBefore(':') }}")
                assertTrue(correct.toDouble() / cases.size >= 0.95, failures.joinToString("\n"))
                assertTrue(failures.isEmpty(), failures.joinToString("\n"))
                assertEquals(19, resolved, "$source resolution coverage changed")

                val image = imageFor(candidate)
                val databasePath = directory.resolve("$source.db").path
                val saved = JdbcSqliteDriver("jdbc:sqlite:$databasePath", schema = KitchenDatabase.Schema).use { driver ->
                    val database = KitchenDatabase(driver).also { it.recipeQueries.enableForeignKeys() }
                    val kitchen = SqlDelightKitchenRepository(database)
                    val recipes = SqlDelightRecipeRepository(database)
                    kitchen.save(KitchenSnapshot(ingredients = catalog + reviewAdditions))
                    SaveReviewedRecipe(recipes, kitchen, fixedClock).save(draft.reviewedRecipe(), image)
                }
                JdbcSqliteDriver("jdbc:sqlite:$databasePath", schema = KitchenDatabase.Schema).use { driver ->
                    val database = KitchenDatabase(driver).also { it.recipeQueries.enableForeignKeys() }
                    val recipes = SqlDelightRecipeRepository(database)
                    assertEquals(saved, recipes.get(saved.id), "$source persisted recipe")
                    assertEquals(image, recipes.getRecipeImage(saved.id), "$source persisted image")
                    assertEquals(cases.map { it.line }, recipes.get(saved.id)?.ingredients?.map { it.rawLine })
                    assertEquals(cases.map { it.reviewedIngredientId }, recipes.get(saved.id)?.ingredients?.map { it.ingredientId })
                    val duplicate = gateway.preview(request, catalog, recipes.getAll()).single { it.draft?.name == RECIPE_NAME }
                    assertEquals(saved.id.toString(), duplicate.duplicateOf, "$source duplicate review")
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private suspend fun imageFor(candidate: RecipeImportCandidate): RecipeImage? = when (val source = candidate.imageSource) {
        is RecipeImportImageSource.PaprikaArchiveEntry -> {
            val loader = DefaultRecipeImageLoader(ZipFileArchiveReader())
            try {
                val image = assertNotNull(loader.load(source))
                val decoded = assertNotNull(withContext(Dispatchers.IO) { ImageIO.read(ByteArrayInputStream(image.bytes)) })
                assertEquals(2, decoded.width)
                assertEquals(2, decoded.height)
                assertEquals("image/jpeg", image.mediaType)
                image
            } finally {
                loader.close()
            }
        }
        is RecipeImportImageSource.Url -> {
            assertEquals(IMAGE_URL, source.url)
            RecipeImage(PaprikaFixture.photoBytes, "image/jpeg")
        }
        null -> null
    }

    private fun CanonicalRecipeDraft.reviewedRecipe() = Recipe(
        name = name,
        servings = servings,
        ingredients = ingredients.mapIndexed { index, ingredient ->
            RecipeIngredient(
                ingredientId = cases[index].reviewedIngredientId,
                quantity = ingredient.quantity,
                quantityHigh = ingredient.quantityHigh,
                unit = ingredient.unit,
                preparation = ingredient.preparation,
                importance = ingredient.importance,
                approximateLevel = ingredient.approximateLevel,
                rawLine = ingredient.rawLine,
            )
        },
        directionsText = directionsText,
        notes = notes,
        categories = categories,
        sourceName = sourceName,
        sourceUrl = sourceUrl,
        prepTimeSeconds = prepTimeSeconds,
        cookTimeSeconds = cookTimeSeconds,
        chillingTimeSeconds = chillingTimeSeconds,
        totalTimeSeconds = totalTimeSeconds,
        imageSourceUrl = imageSourceUrl,
    )

    private fun reviewedPaprikaRecords(): Map<String, String> = cases.associate { case ->
        "recipes/acceptance-${case.id}.json" to buildJsonObject {
            put("uid", "acceptance-${case.id}")
            put("name", "Acceptance ${case.id}")
            put("ingredients", case.line)
            put("directions", "Prepare the fixture ingredient.")
        }.toString()
    } + mapOf(
        "recipes/warned-no-uid.json" to """{"name":"Acceptance no uid","ingredients":"1 cup water","directions":"Heat."}""",
        "recipes/warned-no-ingredients.json" to """{"uid":"no-ingredients","name":"Acceptance empty ingredients","directions":"Review the ingredients."}""",
        "recipes/warned-no-directions.json" to """{"uid":"no-directions","name":"Acceptance empty directions","ingredients":"1 cup water"}""",
        "recipes/source-metadata.json" to """{"uid":"source-metadata","name":"Acceptance source metadata","servings":"4 servings","ingredients":"1 cup plain flour","directions":"Stir.","source":"Acceptance Kitchen","source_url":"https://recipes.example.test/fixture","categories":["Acceptance","Baking"],"notes":"Self-authored fixture."}""",
        "recipes/unicode-metadata.json" to """{"uid":"unicode-metadata","name":"Acceptance café bowl","ingredients":"½ cup plain flour","directions":"Mélangez.","categories":["Déjeuner"],"notes":"Unicode survives import."}""",
    )

    private fun paprikaJson() = buildJsonObject {
        put("uid", "slice13-normalization-corpus")
        put("name", RECIPE_NAME)
        put("servings", "4 servings")
        put("ingredients", cases.joinToString("\n") { it.line })
        put("directions", DIRECTIONS)
        put("categories", JsonArray(listOf(JsonPrimitive("Acceptance"))))
        put("source_url", SOURCE_URL)
        put("photo", "fixture-0002.jpeg")
    }.toString()

    private object CorpusPageFetcher : RecipePageFetcher {
        override suspend fun fetch(url: String) = RecipePageFetchResult.Success(
            RecipePage(
                requestedUrl = url,
                finalUrl = url,
                statusCode = 200,
                contentType = "application/ld+json",
                body = buildJsonObject {
                    put("@context", "https://schema.org")
                    put("@type", "Recipe")
                    put("name", RECIPE_NAME)
                    put("recipeYield", "4 servings")
                    put("recipeIngredient", JsonArray(cases.map { JsonPrimitive(it.line) }))
                    put("recipeInstructions", DIRECTIONS)
                    put("recipeCategory", "Acceptance")
                    put("prepTime", "PT5M")
                    put("cookTime", "PT10M")
                    put("totalTime", "PT15M")
                    put("image", IMAGE_URL)
                }.toString(),
            ),
        )
    }

    private sealed interface ExpectedResolution {
        data class Resolved(val id: IngredientId) : ExpectedResolution
        data object Unresolved : ExpectedResolution
        data class NeedsReview(val ids: Set<IngredientId>) : ExpectedResolution
    }

    private data class ReviewedCase(
        val id: String,
        val line: String,
        val parsedName: String,
        val reviewedIngredientId: IngredientId,
        val expected: ExpectedResolution = ExpectedResolution.Resolved(reviewedIngredientId),
        val quantity: Rational? = Rational.of(1),
        val quantityHigh: Rational? = null,
        val unit: UnitCode? = null,
        val preparation: String? = null,
        val importance: IngredientImportance = IngredientImportance.CRITICAL,
        val approximateLevel: ApproximateLevel = if (quantity == null) ApproximateLevel.ROUGH else ApproximateLevel.EXACT,
    )

    private companion object {
        const val CORPUS_REVISION = "slice13-import-corpus-r1"
        const val RECIPE_NAME = "Slice 13 normalization acceptance"
        const val DIRECTIONS = "Combine the reviewed fixture ingredients."
        const val SOURCE_URL = "https://recipes.example.test/slice13-corpus"
        const val IMAGE_URL = "https://images.example.test/slice13-corpus.jpg"
        val fixedClock = object : Clock {
            override fun now(): Instant = Instant.parse("2026-11-13T12:00:00Z")
        }
        fun id(suffix: Int) = IngredientId(Uuid.parse("00000000-0000-0000-0000-${suffix.toString().padStart(12, '0')}"))
        val onion = id(1301)
        val scallion = id(1302)
        val flour = id(1303)
        val butter = id(1304)
        val blackPepper = id(1305)
        val bellPepper = id(1306)
        val cilantro = id(1307)
        val salt = id(1308)
        val sumac = id(1309)
        val tamarind = id(1310)
        val catalog = listOf(
            Ingredient(onion, "Yellow onion", setOf("onion", "onions", "allium cepa")),
            Ingredient(scallion, "Scallion", setOf("green onion", "spring onion")),
            Ingredient(flour, "All-purpose flour", setOf("plain flour", "AP flour")),
            Ingredient(butter, "Butter", setOf("unsalted butter")),
            Ingredient(blackPepper, "Black pepper", setOf("pepper")),
            Ingredient(bellPepper, "Bell pepper", setOf("pepper")),
            Ingredient(cilantro, "Cilantro", setOf("coriander leaves")),
            Ingredient(salt, "Salt"),
        )
        val reviewAdditions = listOf(Ingredient(sumac, "Sumac"), Ingredient(tamarind, "Tamarind paste"))
        val unresolved = ExpectedResolution.Unresolved
        val ambiguous = ExpectedResolution.NeedsReview(setOf(bellPepper, blackPepper))
        val cases = listOf(
            ReviewedCase("canonical-onion", "1 yellow onion", "yellow onion", onion),
            ReviewedCase("canonical-scallion", "2 scallion", "scallion", scallion, quantity = Rational.of(2)),
            ReviewedCase("canonical-flour", "1 cup all-purpose flour", "all-purpose flour", flour, unit = UnitCode.CUP),
            ReviewedCase("canonical-butter", "2 tbsp butter", "butter", butter, quantity = Rational.of(2), unit = UnitCode.TABLESPOON),
            ReviewedCase("alias-onions", "3 onions", "onions", onion, quantity = Rational.of(3)),
            ReviewedCase("alias-botanical", "1 allium cepa", "allium cepa", onion),
            ReviewedCase("alias-green-onion", "1 green onion", "green onion", scallion),
            ReviewedCase("alias-spring-onion", "2 spring onion", "spring onion", scallion, quantity = Rational.of(2)),
            ReviewedCase("mixed-fraction-flour", "1 1/2 cups plain flour", "plain flour", flour, quantity = Rational.of(3, 2), unit = UnitCode.CUP),
            ReviewedCase("unicode-ap-flour", "¼ cup AP flour", "AP flour", flour, quantity = Rational.of(1, 4), unit = UnitCode.CUP),
            ReviewedCase("prepared-butter", "2 tbsp unsalted butter, melted", "unsalted butter", butter, quantity = Rational.of(2), unit = UnitCode.TABLESPOON, preparation = "melted"),
            ReviewedCase("alias-cilantro", "coriander leaves", "coriander leaves", cilantro, quantity = null),
            ReviewedCase("punctuation-onion", "1 YELLOW-ONION!!!", "YELLOW-ONION!!!", onion),
            ReviewedCase("underscore-scallion", "1 GREEN_ONION", "GREEN_ONION", scallion),
            ReviewedCase("unicode-case-flour", "½ cup ALL-PURPOSE flour", "ALL-PURPOSE flour", flour, quantity = Rational.of(1, 2), unit = UnitCode.CUP),
            ReviewedCase("punctuation-black-pepper", "BLACK.PEPPER", "BLACK.PEPPER", blackPepper, quantity = null),
            ReviewedCase("hyphen-bell-pepper", "1 bell-pepper", "bell-pepper", bellPepper),
            ReviewedCase("descriptive-onion", "1 large yellow onion", "large yellow onion", onion, expected = unresolved),
            ReviewedCase("descriptive-preparation", "2 tbsp finely chopped onion", "finely chopped onion", onion, expected = unresolved, quantity = Rational.of(2), unit = UnitCode.TABLESPOON),
            ReviewedCase("descriptive-pepper", "1 ripe bell pepper", "ripe bell pepper", bellPepper, expected = unresolved),
            ReviewedCase("unknown-sumac", "1 tsp sumac", "sumac", sumac, expected = unresolved, unit = UnitCode.TEASPOON),
            ReviewedCase("range-unknown-tamarind", "2-3 tbsp tamarind paste", "tamarind paste", tamarind, expected = unresolved, quantity = Rational.of(2), quantityHigh = Rational.of(3), unit = UnitCode.TABLESPOON),
            ReviewedCase("ambiguous-pepper", "1 tsp pepper", "pepper", bellPepper, expected = ambiguous, unit = UnitCode.TEASPOON),
            ReviewedCase("ambiguous-punctuation", "PEPPER!", "PEPPER!", blackPepper, expected = ambiguous, quantity = null),
            ReviewedCase("optional-salt", "1 tsp salt (optional)", "salt", salt, unit = UnitCode.TEASPOON, importance = IngredientImportance.OPTIONAL),
            ReviewedCase("approximate-flour", "~1 cup plain flour", "plain flour", flour, unit = UnitCode.CUP, approximateLevel = ApproximateLevel.MODERATE),
        )

        fun resolutionOutcome(resolution: IngredientResolution): ExpectedResolution = when (resolution) {
            is IngredientResolution.Resolved -> ExpectedResolution.Resolved(resolution.ingredient.id)
            is IngredientResolution.Unresolved -> ExpectedResolution.Unresolved
            is IngredientResolution.NeedsReview -> ExpectedResolution.NeedsReview(resolution.candidates.map { it.id }.toSet())
        }
    }
}
