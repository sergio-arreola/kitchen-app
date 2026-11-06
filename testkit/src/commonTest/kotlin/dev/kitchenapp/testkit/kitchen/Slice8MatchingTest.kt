package dev.kitchenapp.testkit.kitchen

import dev.kitchenapp.core.domain.enums.IngredientImportance
import dev.kitchenapp.core.domain.household.EquipmentRequirement
import dev.kitchenapp.core.domain.household.EquipmentRequirementKind
import dev.kitchenapp.core.domain.household.EquipmentStatus
import dev.kitchenapp.core.domain.household.HouseholdIngredientRules
import dev.kitchenapp.core.domain.household.IngredientPreference
import dev.kitchenapp.core.domain.identifiers.RecipeId
import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.core.domain.quantity.QuantityStatus
import dev.kitchenapp.core.domain.quantity.ReadinessStatus
import dev.kitchenapp.core.domain.recipe.Recipe
import dev.kitchenapp.core.domain.recipe.RecipeIngredient
import dev.kitchenapp.core.engine.matching.CandidateStatus
import dev.kitchenapp.core.engine.matching.FreshnessStatus
import dev.kitchenapp.core.engine.matching.MatchExclusion
import dev.kitchenapp.core.engine.matching.MealQuery
import dev.kitchenapp.core.engine.matching.RecipeMatchMetadata
import dev.kitchenapp.core.engine.matching.RecipeMatcher
import dev.kitchenapp.core.engine.matching.ShoppingAggregator
import dev.kitchenapp.core.domain.units.UnitCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Slice8MatchingTest {
    @Test
    fun `fixture outcomes are reproducible and explainable`() {
        val first = Slice8KitchenSimulation.evaluate()
        val second = Slice8KitchenSimulation.evaluate()

        assertEquals(first, second)
        assertEquals(
            Slice8KitchenSimulation.expectedStatus,
            first.associate { it.recipe.name to it.status },
        )

        val skillet = first.single { it.recipe == Slice8KitchenSimulation.skillet }
        val spinach = skillet.ingredients.single { it.requirement.ingredientId == Slice8KitchenSimulation.spinach }
        assertEquals(FreshnessStatus.USE_SOON, spinach.freshness.single().status)
        assertEquals(EquipmentStatus.WARNING, skillet.equipment.reasons.single { it.requested == "Blender" }.status)
        assertEquals(5, skillet.score.equipmentWarningPenalty)
    }

    @Test
    fun `quantity shortages never produce ready now`() {
        val input = Slice8KitchenSimulation.input.copy(
            query = Slice8KitchenSimulation.input.query.copy(requiredIngredients = emptySet()),
        )
        val rice = RecipeMatcher().match(input).single { it.recipe == Slice8KitchenSimulation.riceBowl }

        assertEquals(CandidateStatus.INCLUDED, rice.status)
        assertEquals(ReadinessStatus.NOT_READY, rice.readiness)
        assertEquals(QuantityStatus.INSUFFICIENT, rice.ingredients.single().quantity.status)
    }

    @Test
    fun `hard exclusions and unavailable required equipment exclude candidates`() {
        val satay = Slice8KitchenSimulation.evaluate()
            .single { it.recipe == Slice8KitchenSimulation.excludedSatay }

        assertEquals(CandidateStatus.EXCLUDED, satay.status)
        assertTrue(MatchExclusion.HARD_INGREDIENT_EXCLUSION in satay.exclusions)
        assertTrue(MatchExclusion.EQUIPMENT_UNAVAILABLE in satay.exclusions)
        assertFalse(satay.equipment.isAllowed)
    }

    @Test
    fun `time and serving filters reject mismatched candidates`() {
        val slow = Slice8KitchenSimulation.evaluate()
            .single { it.recipe == Slice8KitchenSimulation.slowBreakfast }

        assertEquals(CandidateStatus.EXCLUDED, slow.status)
        assertTrue(MatchExclusion.TIME_LIMIT in slow.exclusions)
        assertTrue(MatchExclusion.SERVINGS_MISMATCH in slow.exclusions)
    }

    @Test
    fun `use soon ingredients break an otherwise equal ranking tie`() {
        val freshBreakfast = Recipe(
            id = RecipeId.generate(),
            name = "Eggs only",
            ingredients = listOf(
                RecipeIngredient(
                    ingredientId = Slice8KitchenSimulation.eggs,
                    quantity = Rational.of(2),
                    unit = UnitCode.EGG,
                    importance = IngredientImportance.CRITICAL,
                    rawLine = "2 eggs",
                ),
            ),
            directionsText = "Cook the eggs.",
            categories = listOf("Breakfast"),
        )
        val input = Slice8KitchenSimulation.input.copy(
            recipes = listOf(Slice8KitchenSimulation.skillet, freshBreakfast),
            availableEquipment = setOf("Skillet", "Blender"),
            householdRules = HouseholdIngredientRules(
                preferences = listOf(IngredientPreference(Slice8KitchenSimulation.eggs, score = 2)),
            ),
            query = MealQuery(
                mealTypes = setOf("breakfast"),
                availableTimeMinutes = 30,
                servings = 2,
                requiredIngredients = setOf(Slice8KitchenSimulation.eggs),
            ),
            metadata = mapOf(
                Slice8KitchenSimulation.skillet.id to RecipeMatchMetadata(
                    mealTypes = setOf("Breakfast"),
                    estimatedMinutes = 15,
                    servings = 2,
                    equipmentRequirements = listOf(
                        EquipmentRequirement("Skillet", EquipmentRequirementKind.REQUIRED),
                    ),
                ),
                freshBreakfast.id to RecipeMatchMetadata(
                    mealTypes = setOf("Breakfast"),
                    estimatedMinutes = 15,
                    servings = 2,
                ),
            ),
        )

        val ranked = RecipeMatcher().match(input)

        assertEquals(Slice8KitchenSimulation.skillet, ranked.first().recipe)
        val skillet = ranked.single { it.recipe == Slice8KitchenSimulation.skillet }
        val eggs = ranked.single { it.recipe == freshBreakfast }
        assertEquals(4, skillet.score.useSoonBonus)
        assertEquals(0, eggs.score.useSoonBonus)
        assertEquals(2, skillet.score.preferencePenalty)
        assertEquals(2, eggs.score.preferencePenalty)
    }

    @Test
    fun `shopping list aggregates remaining compatible quantities`() {
        val secondRiceRecipe = Recipe(
            id = RecipeId.generate(),
            name = "Rice side",
            ingredients = listOf(
                RecipeIngredient(
                    ingredientId = Slice8KitchenSimulation.rice,
                    quantity = Rational.of(32),
                    unit = UnitCode.TABLESPOON,
                    rawLine = "32 tablespoons rice",
                ),
            ),
            directionsText = "Cook rice.",
            categories = listOf("Dinner"),
        )
        val input = Slice8KitchenSimulation.input.copy(
            recipes = Slice8KitchenSimulation.input.recipes + secondRiceRecipe,
            query = Slice8KitchenSimulation.input.query.copy(requiredIngredients = emptySet()),
            metadata = Slice8KitchenSimulation.input.metadata + (
                secondRiceRecipe.id to RecipeMatchMetadata(
                    mealTypes = setOf("Dinner"),
                    estimatedMinutes = 20,
                    servings = 2,
                )
            ),
        )
        val lines = ShoppingAggregator().aggregate(RecipeMatcher().match(input))

        assertEquals(1, lines.size)
        assertEquals(Slice8KitchenSimulation.rice, lines.single().ingredientId)
        assertEquals(Rational.of(2), lines.single().quantity)
        assertEquals(UnitCode.CUP, lines.single().unit)
        assertEquals(
            setOf(Slice8KitchenSimulation.riceBowl.id, secondRiceRecipe.id),
            lines.single().recipeIds,
        )
    }
}
