package dev.kitchenapp.testkit.kitchen

import dev.kitchenapp.core.domain.enums.ApproximateLevel
import dev.kitchenapp.core.domain.enums.IngredientImportance
import dev.kitchenapp.core.domain.identifiers.IngredientId
import dev.kitchenapp.core.domain.identifiers.RecipeId
import dev.kitchenapp.core.domain.inventory.InventoryAmount
import dev.kitchenapp.core.domain.inventory.InventoryLot
import dev.kitchenapp.core.domain.inventory.KitchenInventory
import dev.kitchenapp.core.domain.inventory.KitchenLocation
import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.core.domain.quantity.QuantityStatus
import dev.kitchenapp.core.domain.quantity.ReadinessStatus
import dev.kitchenapp.core.domain.recipe.Recipe
import dev.kitchenapp.core.domain.recipe.RecipeIngredient
import dev.kitchenapp.core.domain.time.DomainLocalDate
import dev.kitchenapp.core.domain.units.UnitCode
import dev.kitchenapp.core.engine.matching.CandidateStatus
import dev.kitchenapp.core.engine.matching.MealMatchInput
import dev.kitchenapp.core.engine.matching.MealQuery
import dev.kitchenapp.core.engine.matching.RecipeMatcher
import dev.kitchenapp.core.engine.matching.ShoppingAggregator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class Slice13MatchingAcceptanceTest {
    @Test
    fun `reviewed quantity corpus has no false ready results and explains every shopping gap`() {
        val falseReady = mutableListOf<String>()
        val failures = mutableListOf<String>()
        cases.forEachIndexed { index, case ->
            val requirement = RecipeIngredient(
                ingredientId = ingredient,
                quantity = case.needed,
                quantityHigh = case.high,
                unit = case.unit,
                importance = case.importance,
                approximateLevel = case.approximateLevel,
                rawLine = case.id,
            )
            val recipe = Recipe(
                id = RecipeId(Uuid.parse("00000000-0000-0000-0000-${(13000 + index).toString().padStart(12, '0')}")),
                name = case.id,
                ingredients = listOf(requirement),
                directionsText = "Prepare this self-authored benchmark case.",
            )
            val input = MealMatchInput(
                recipes = listOf(recipe),
                inventory = KitchenInventory(
                    locations = listOf(location),
                    lots = case.stock.map { amount ->
                        InventoryLot(ingredientId = ingredient, locationId = location.id, amount = amount)
                    },
                ),
                query = MealQuery(),
                asOfDate = DomainLocalDate.of(2026, 11, 13),
            )
            val result = RecipeMatcher().match(input).single()
            assertEquals(result, RecipeMatcher().match(input).single(), "${case.id} determinism")
            assertEquals(CandidateStatus.INCLUDED, result.status, "${case.id} must not be hidden by filters")
            assertTrue(result.exclusions.isEmpty(), case.id)
            val explanation = result.ingredients.single()
            val shopping = ShoppingAggregator().aggregate(listOf(result))
            if (case.readiness != ReadinessStatus.READY && result.readiness == ReadinessStatus.READY) {
                falseReady += case.id
            }
            val expectedScore = when (case.readiness) {
                ReadinessStatus.READY -> 100
                ReadinessStatus.NEEDS_CONFIRMATION -> 50
                ReadinessStatus.NOT_READY -> 0
            }
            if (result.readiness != case.readiness || explanation.quantity.status != case.quantityStatus ||
                explanation.quantity.reason.knownAvailable != case.available ||
                explanation.quantity.reason.message.isBlank() || result.score.readiness != expectedScore ||
                shopping.size != (if (case.shopping) 1 else 0) ||
                (case.shopping && (shopping.single().quantity != case.gap || shopping.single().unit != case.unit ||
                    shopping.single().recipeIds != setOf(recipe.id)))
            ) {
                failures += "${case.id}: expected=$case, readiness=${result.readiness}, quantity=${explanation.quantity}, shopping=$shopping"
            }
        }
        println("slice13-matching-corpus-r1 cases=${cases.size}; falseReady=${falseReady.size}; falseReadyIds=$falseReady; failedCaseIds=${failures.map { it.substringBefore(':') }}")
        assertEquals(0, falseReady.size, "False Ready Now case IDs: $falseReady")
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `repeated ingredient rows cannot each reuse the whole inventory lot`() {
        val recipe = Recipe(
            name = "Repeated egg rows",
            ingredients = listOf(
                RecipeIngredient(ingredientId = ingredient, quantity = Rational.of(2), unit = UnitCode.EGG, rawLine = "2 eggs for the base"),
                RecipeIngredient(ingredientId = ingredient, quantity = Rational.of(2), unit = UnitCode.EGG, rawLine = "2 eggs for the topping"),
            ),
            directionsText = "Use both measured portions.",
        )
        val input = MealMatchInput(
            recipes = listOf(recipe),
            inventory = KitchenInventory(
                locations = listOf(location),
                lots = listOf(InventoryLot(ingredientId = ingredient, locationId = location.id, amount = exact(2, UnitCode.EGG))),
            ),
            asOfDate = DomainLocalDate.of(2026, 11, 13),
        )

        val result = RecipeMatcher().match(input).single()

        assertEquals(CandidateStatus.INCLUDED, result.status)
        assertEquals(ReadinessStatus.NOT_READY, result.readiness)
        assertTrue(result.ingredients.any { it.quantity.status == QuantityStatus.INSUFFICIENT })
        val shopping = ShoppingAggregator().aggregate(listOf(result)).single()
        assertEquals(Rational.of(2), shopping.quantity)
        assertEquals(UnitCode.EGG, shopping.unit)
    }

    @Test
    fun `repeated compatible unit rows consume stock only once`() {
        val recipe = Recipe(
            name = "Repeated compatible flour rows",
            ingredients = listOf(
                RecipeIngredient(ingredientId = ingredient, quantity = Rational.of(1, 2), unit = UnitCode.CUP, rawLine = "1/2 cup flour for the base"),
                RecipeIngredient(ingredientId = ingredient, quantity = Rational.of(16), unit = UnitCode.TABLESPOON, rawLine = "16 tbsp flour for the topping"),
            ),
            directionsText = "Use both measured portions.",
        )
        val input = MealMatchInput(
            recipes = listOf(recipe),
            inventory = KitchenInventory(
                locations = listOf(location),
                lots = listOf(InventoryLot(ingredientId = ingredient, locationId = location.id, amount = exact(1, UnitCode.CUP))),
            ),
            asOfDate = DomainLocalDate.of(2026, 11, 13),
        )

        val result = RecipeMatcher().match(input).single()
        val shopping = ShoppingAggregator().aggregate(listOf(result)).single()

        assertEquals(ReadinessStatus.NOT_READY, result.readiness)
        val gapInCups = dev.kitchenapp.core.domain.quantity.UnitConverter().convert(
            requireNotNull(shopping.quantity),
            requireNotNull(shopping.unit),
            UnitCode.CUP,
            ingredient,
        )
        assertEquals(Rational.of(1, 2), gapInCups)
    }

    @Test
    fun `optional duplicate row does not reserve stock needed by required row`() {
        val recipe = Recipe(
            name = "Optional egg topping",
            ingredients = listOf(
                RecipeIngredient(ingredientId = ingredient, quantity = Rational.of(2), unit = UnitCode.EGG, importance = IngredientImportance.OPTIONAL, rawLine = "2 optional eggs"),
                RecipeIngredient(ingredientId = ingredient, quantity = Rational.of(2), unit = UnitCode.EGG, rawLine = "2 required eggs"),
            ),
            directionsText = "Make the base and choose whether to add the topping.",
        )
        val input = MealMatchInput(
            recipes = listOf(recipe),
            inventory = KitchenInventory(
                locations = listOf(location),
                lots = listOf(InventoryLot(ingredientId = ingredient, locationId = location.id, amount = exact(2, UnitCode.EGG))),
            ),
            asOfDate = DomainLocalDate.of(2026, 11, 13),
        )

        val result = RecipeMatcher().match(input).single()

        assertEquals(ReadinessStatus.READY, result.readiness)
        assertEquals(listOf(QuantityStatus.NOT_REQUIRED, QuantityStatus.AVAILABLE), result.ingredients.map { it.quantity.status })
        assertTrue(ShoppingAggregator().aggregate(listOf(result)).isEmpty())
    }

    @Test
    fun `unknown duplicate quantity requires confirmation beside a measured row`() {
        val unknown = RecipeIngredient(ingredientId = ingredient, rawLine = "flour for dusting")
        val measured = RecipeIngredient(ingredientId = ingredient, quantity = Rational.of(1), unit = UnitCode.CUP, rawLine = "1 cup flour")
        listOf(listOf(unknown, measured), listOf(measured, unknown)).forEach { rows ->
            val recipe = Recipe(name = "Unmeasured flour use", ingredients = rows, directionsText = "Use both portions.")
            val input = MealMatchInput(
                recipes = listOf(recipe),
                inventory = KitchenInventory(
                    locations = listOf(location),
                    lots = listOf(InventoryLot(ingredientId = ingredient, locationId = location.id, amount = exact(1, UnitCode.CUP))),
                ),
                asOfDate = DomainLocalDate.of(2026, 11, 13),
            )

            val result = RecipeMatcher().match(input).single()

            assertTrue(result.readiness != ReadinessStatus.READY, "Unknown consumption cannot reuse measured stock: $rows")
            assertTrue(result.ingredients.any { it.quantity.status != QuantityStatus.AVAILABLE })
            assertEquals(result, RecipeMatcher().match(input).single())
        }
    }

    private data class ReviewedQuantityCase(
        val id: String,
        val needed: Rational? = Rational.of(1),
        val high: Rational? = null,
        val unit: UnitCode? = UnitCode.CUP,
        val stock: List<InventoryAmount> = emptyList(),
        val quantityStatus: QuantityStatus,
        val readiness: ReadinessStatus,
        val available: Rational? = null,
        val shopping: Boolean = false,
        val gap: Rational? = null,
        val importance: IngredientImportance = IngredientImportance.CRITICAL,
        val approximateLevel: ApproximateLevel = ApproximateLevel.EXACT,
    )

    private companion object {
        val ingredient = IngredientId(Uuid.parse("00000000-0000-0000-0000-000000013001"))
        val location = KitchenLocation(name = "Acceptance pantry")
        fun exact(value: Long, unit: UnitCode) = InventoryAmount.Exact(Rational.of(value), unit)
        val cases = listOf(
            ReviewedQuantityCase("exact-equal", stock = listOf(exact(1, UnitCode.CUP)), quantityStatus = QuantityStatus.AVAILABLE, readiness = ReadinessStatus.READY, available = Rational.of(1)),
            ReviewedQuantityCase("exact-shortage", needed = Rational.of(2), stock = listOf(exact(1, UnitCode.CUP)), quantityStatus = QuantityStatus.INSUFFICIENT, readiness = ReadinessStatus.NOT_READY, available = Rational.of(1), shopping = true, gap = Rational.of(1)),
            ReviewedQuantityCase("missing-measured", quantityStatus = QuantityStatus.MISSING, readiness = ReadinessStatus.NOT_READY, shopping = true, gap = Rational.of(1)),
            ReviewedQuantityCase("multiple-lots-sufficient", needed = Rational.of(2), stock = listOf(exact(1, UnitCode.CUP), exact(1, UnitCode.CUP)), quantityStatus = QuantityStatus.AVAILABLE, readiness = ReadinessStatus.READY, available = Rational.of(2)),
            ReviewedQuantityCase("multiple-lots-short", needed = Rational.of(3), stock = listOf(exact(1, UnitCode.CUP), exact(1, UnitCode.CUP)), quantityStatus = QuantityStatus.INSUFFICIENT, readiness = ReadinessStatus.NOT_READY, available = Rational.of(2), shopping = true, gap = Rational.of(1)),
            ReviewedQuantityCase("compatible-volume", stock = listOf(exact(16, UnitCode.TABLESPOON)), quantityStatus = QuantityStatus.AVAILABLE, readiness = ReadinessStatus.READY, available = Rational.of(1)),
            ReviewedQuantityCase("compatible-volume-short", needed = Rational.of(2), stock = listOf(exact(16, UnitCode.TABLESPOON)), quantityStatus = QuantityStatus.INSUFFICIENT, readiness = ReadinessStatus.NOT_READY, available = Rational.of(1), shopping = true, gap = Rational.of(1)),
            ReviewedQuantityCase("compatible-mass", unit = UnitCode.KILOGRAM, stock = listOf(exact(1000, UnitCode.GRAM)), quantityStatus = QuantityStatus.AVAILABLE, readiness = ReadinessStatus.READY, available = Rational.of(1)),
            ReviewedQuantityCase("compatible-mass-short", unit = UnitCode.KILOGRAM, stock = listOf(exact(250, UnitCode.GRAM)), quantityStatus = QuantityStatus.INSUFFICIENT, readiness = ReadinessStatus.NOT_READY, available = Rational.of(1, 4), shopping = true, gap = Rational.of(3, 4)),
            ReviewedQuantityCase("unreviewed-mass-volume", stock = listOf(exact(500, UnitCode.GRAM)), quantityStatus = QuantityStatus.UNCERTAIN, readiness = ReadinessStatus.NEEDS_CONFIRMATION),
            ReviewedQuantityCase("presence-measured", needed = Rational.of(2), unit = UnitCode.EGG, stock = listOf(InventoryAmount.PresenceOnly), quantityStatus = QuantityStatus.UNCERTAIN, readiness = ReadinessStatus.NEEDS_CONFIRMATION),
            ReviewedQuantityCase("presence-unspecified", needed = null, unit = null, stock = listOf(InventoryAmount.PresenceOnly), quantityStatus = QuantityStatus.AVAILABLE, readiness = ReadinessStatus.READY),
            ReviewedQuantityCase("approximate-stock", stock = listOf(InventoryAmount.Approximate(Rational.of(2), UnitCode.CUP)), quantityStatus = QuantityStatus.UNCERTAIN, readiness = ReadinessStatus.NEEDS_CONFIRMATION, available = Rational.of(2)),
            ReviewedQuantityCase("mixed-exact-approximate", needed = Rational.of(2), stock = listOf(exact(1, UnitCode.CUP), InventoryAmount.Approximate(Rational.of(1), UnitCode.CUP)), quantityStatus = QuantityStatus.UNCERTAIN, readiness = ReadinessStatus.NEEDS_CONFIRMATION, available = Rational.of(2)),
            ReviewedQuantityCase("optional-missing", quantityStatus = QuantityStatus.NOT_REQUIRED, readiness = ReadinessStatus.READY, importance = IngredientImportance.OPTIONAL),
            ReviewedQuantityCase("range-below-upper", needed = Rational.of(2), high = Rational.of(3), stock = listOf(exact(2, UnitCode.CUP)), quantityStatus = QuantityStatus.UNCERTAIN, readiness = ReadinessStatus.NEEDS_CONFIRMATION, available = Rational.of(2)),
            ReviewedQuantityCase("range-upper-covered", needed = Rational.of(2), high = Rational.of(3), stock = listOf(exact(3, UnitCode.CUP)), quantityStatus = QuantityStatus.AVAILABLE, readiness = ReadinessStatus.READY, available = Rational.of(3)),
            ReviewedQuantityCase("approximate-requirement", stock = listOf(exact(1, UnitCode.CUP)), quantityStatus = QuantityStatus.UNCERTAIN, readiness = ReadinessStatus.NEEDS_CONFIRMATION, available = Rational.of(1), approximateLevel = ApproximateLevel.MODERATE),
            ReviewedQuantityCase("unknown-unit", stock = listOf(exact(1, UnitCode.UNKNOWN)), quantityStatus = QuantityStatus.UNCERTAIN, readiness = ReadinessStatus.NEEDS_CONFIRMATION),
            ReviewedQuantityCase("zero-stock", stock = listOf(exact(0, UnitCode.CUP)), quantityStatus = QuantityStatus.INSUFFICIENT, readiness = ReadinessStatus.NOT_READY, shopping = true, gap = Rational.of(1)),
            ReviewedQuantityCase("scaled-six-eggs", needed = Rational.of(6), unit = UnitCode.EGG, stock = listOf(exact(3, UnitCode.EGG)), quantityStatus = QuantityStatus.INSUFFICIENT, readiness = ReadinessStatus.NOT_READY, available = Rational.of(3), shopping = true, gap = Rational.of(3)),
            ReviewedQuantityCase("missing-unspecified", needed = null, unit = null, quantityStatus = QuantityStatus.MISSING, readiness = ReadinessStatus.NOT_READY, shopping = true),
        )
    }
}
