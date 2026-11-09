package dev.kitchenapp.core.domain.quantity

import dev.kitchenapp.core.domain.enums.ApproximateLevel
import dev.kitchenapp.core.domain.enums.IngredientImportance
import dev.kitchenapp.core.domain.household.EquipmentRequirement
import dev.kitchenapp.core.domain.household.EquipmentRequirementKind
import dev.kitchenapp.core.domain.household.EquipmentStatus
import dev.kitchenapp.core.domain.household.HouseholdIngredientRules
import dev.kitchenapp.core.domain.household.IngredientPreference
import dev.kitchenapp.core.domain.identifiers.IngredientId
import dev.kitchenapp.core.domain.inventory.InventoryAmount
import dev.kitchenapp.core.domain.inventory.InventoryLot
import dev.kitchenapp.core.domain.inventory.KitchenLocation
import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.core.domain.recipe.RecipeIngredient
import dev.kitchenapp.core.domain.units.UnitCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Slice7EvaluationTest {
    private val ingredientId = IngredientId.generate()
    private val location = KitchenLocation(name = "Pantry")

    @Test
    fun `converts compatible units but refuses unreviewed cross dimensions`() {
        val converter = UnitConverter()
        assertEquals(
            Rational.of(1000),
            converter.convert(Rational.of(1), UnitCode.KILOGRAM, UnitCode.GRAM),
        )
        assertEquals(
            Rational.of(16),
            converter.convert(Rational.of(1), UnitCode.CUP, UnitCode.TABLESPOON),
        )
        assertNull(converter.convert(Rational.of(1), UnitCode.CUP, UnitCode.GRAM))

        val reviewed = UnitConverter(
            listOf(
                UnitConversionRule(
                    from = UnitCode.CUP,
                    to = UnitCode.GRAM,
                    factor = Rational.of(120),
                    ingredientId = ingredientId,
                ),
            ),
        )
        assertEquals(
            Rational.of(120),
            reviewed.convert(Rational.of(1), UnitCode.CUP, UnitCode.GRAM, ingredientId),
        )
        assertNull(reviewed.convert(Rational.of(1), UnitCode.CUP, UnitCode.GRAM))

        val genericRule = UnitConverter(
            listOf(UnitConversionRule(UnitCode.CUP, UnitCode.GRAM, Rational.of(120))),
        )
        assertEquals(
            Rational.of(120),
            genericRule.convert(Rational.of(1), UnitCode.CUP, UnitCode.GRAM, IngredientId.generate()),
        )
    }

    @Test
    fun `exact count prevents false ready when two eggs cannot cover six`() {
        val requirement = requirement(quantity = 6, unit = UnitCode.EGG)
        val lot = lot(InventoryAmount.Exact(Rational.of(2), UnitCode.EGG))

        val result = QuantityEvaluator().evaluate(requirement, listOf(lot))
        assertEquals(QuantityStatus.INSUFFICIENT, result.status)
        assertEquals(ReadinessStatus.NOT_READY, evaluateReadiness(listOf(result)).status)
        assertEquals(Rational.of(2), result.reason.knownAvailable)
    }

    @Test
    fun `presence only and approximate amounts require confirmation for measured lines`() {
        val requirement = requirement(quantity = 1, unit = UnitCode.CUP)
        val evaluator = QuantityEvaluator()

        val presence = evaluator.evaluate(requirement, listOf(lot(InventoryAmount.PresenceOnly)))
        assertEquals(QuantityStatus.UNCERTAIN, presence.status)
        assertEquals(ReadinessStatus.NEEDS_CONFIRMATION, evaluateReadiness(listOf(presence)).status)

        val approximate = evaluator.evaluate(
            requirement,
            listOf(lot(InventoryAmount.Approximate(Rational.of(2), UnitCode.CUP))),
        )
        assertEquals(QuantityStatus.UNCERTAIN, approximate.status)
    }

    @Test
    fun `exact compatible lots aggregate and incompatible units remain uncertain`() {
        val requirement = requirement(quantity = 1, unit = UnitCode.CUP)
        val exactLots = listOf(
            lot(InventoryAmount.Exact(Rational.of(8), UnitCode.TABLESPOON)),
            lot(InventoryAmount.Exact(Rational.of(8), UnitCode.TABLESPOON)),
        )
        assertEquals(QuantityStatus.AVAILABLE, QuantityEvaluator().evaluate(requirement, exactLots).status)

        val unconvertible = lot(InventoryAmount.Exact(Rational.of(1), UnitCode.GRAM))
        assertEquals(
            QuantityStatus.UNCERTAIN,
            QuantityEvaluator().evaluate(requirement, listOf(unconvertible)).status,
        )
    }

    @Test
    fun `non exact recipe quantity cannot produce ready without an upper bound`() {
        val requirement = requirement(quantity = 1, unit = UnitCode.CUP).copy(
            approximateLevel = ApproximateLevel.MODERATE,
        )
        val result = QuantityEvaluator().evaluate(
            requirement,
            listOf(lot(InventoryAmount.Exact(Rational.of(1), UnitCode.CUP))),
        )
        assertEquals(QuantityStatus.UNCERTAIN, result.status)
    }

    @Test
    fun `exact stock covering a stated upper bound is sufficient`() {
        val requirement = requirement(quantity = 1, unit = UnitCode.CUP).copy(
            quantityHigh = Rational.of(2),
        )
        val result = QuantityEvaluator().evaluate(
            requirement,
            listOf(lot(InventoryAmount.Exact(Rational.of(2), UnitCode.CUP))),
        )
        assertEquals(QuantityStatus.AVAILABLE, result.status)
    }

    @Test
    fun `optional ingredient does not block readiness and hard exclusion blocks candidate`() {
        val optional = requirement(quantity = 1, unit = UnitCode.PIECE).copy(
            importance = IngredientImportance.OPTIONAL,
        )
        val evaluation = QuantityEvaluator().evaluate(optional, emptyList())
        assertEquals(QuantityStatus.NOT_REQUIRED, evaluation.status)
        assertEquals(ReadinessStatus.READY, evaluateReadiness(listOf(evaluation)).status)

        val rules = HouseholdIngredientRules(
            hardExclusions = setOf(ingredientId),
            preferences = emptyList(),
        )
        val excluded = rules.evaluate(setOf(ingredientId))
        assertFalse(excluded.isAllowed)
        assertEquals(setOf(ingredientId), excluded.excludedIngredients)

        val allergen = HouseholdIngredientRules(allergens = setOf(ingredientId))
            .evaluate(setOf(ingredientId))
        assertFalse(allergen.isAllowed)
        assertEquals(setOf(ingredientId), allergen.excludedIngredients)
    }

    @Test
    fun `soft preferences add penalty and equipment substitutes are explained`() {
        val rules = HouseholdIngredientRules(
            preferences = listOf(IngredientPreference(ingredientId, score = 3)),
        )
        val preference = rules.evaluate(setOf(ingredientId))
        assertTrue(preference.isAllowed)
        assertEquals(3, preference.softPreferencePenalty)
        assertEquals(setOf(ingredientId), preference.dislikedIngredients)

        val evaluation = dev.kitchenapp.core.domain.household.evaluateEquipment(
            requirements = listOf(
                EquipmentRequirement(
                    equipment = "Blender",
                    kind = EquipmentRequirementKind.SUBSTITUTABLE,
                    acceptableSubstitutes = setOf("food processor"),
                ),
                EquipmentRequirement(
                    equipment = "Stand mixer",
                    kind = EquipmentRequirementKind.PREFERRED,
                ),
            ),
            availableEquipment = setOf("Food Processor"),
        )
        assertTrue(evaluation.isAllowed)
        assertEquals(1, evaluation.warningCount)
        assertEquals(EquipmentStatus.SATISFIED, evaluation.reasons[0].status)
        assertEquals("food processor", evaluation.reasons[0].satisfiedBy)
        assertEquals(EquipmentStatus.WARNING, evaluation.reasons[1].status)
    }

    private fun requirement(quantity: Long, unit: UnitCode) = RecipeIngredient(
        ingredientId = ingredientId,
        quantity = Rational.of(quantity),
        unit = unit,
        rawLine = "$quantity ${unit.displayName}",
    )

    private fun lot(amount: InventoryAmount) = InventoryLot(
        ingredientId = ingredientId,
        locationId = location.id,
        amount = amount,
    )
}
