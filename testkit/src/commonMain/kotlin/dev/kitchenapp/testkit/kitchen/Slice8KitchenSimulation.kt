package dev.kitchenapp.testkit.kitchen

import dev.kitchenapp.core.domain.household.EquipmentRequirement
import dev.kitchenapp.core.domain.household.EquipmentRequirementKind
import dev.kitchenapp.core.domain.household.HouseholdIngredientRules
import dev.kitchenapp.core.domain.identifiers.IngredientId
import dev.kitchenapp.core.domain.identifiers.RecipeId
import dev.kitchenapp.core.domain.inventory.FreshnessPolicy
import dev.kitchenapp.core.domain.inventory.InventoryAmount
import dev.kitchenapp.core.domain.inventory.InventoryLot
import dev.kitchenapp.core.domain.inventory.KitchenInventory
import dev.kitchenapp.core.domain.inventory.KitchenLocation
import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.core.domain.recipe.Recipe
import dev.kitchenapp.core.domain.recipe.RecipeIngredient
import dev.kitchenapp.core.domain.time.DomainLocalDate
import dev.kitchenapp.core.engine.matching.CandidateStatus
import dev.kitchenapp.core.engine.matching.MealMatchInput
import dev.kitchenapp.core.engine.matching.MealQuery
import dev.kitchenapp.core.engine.matching.RecipeMatchMetadata
import dev.kitchenapp.core.engine.matching.RecipeMatcher
import dev.kitchenapp.core.engine.matching.ShoppingAggregator
import dev.kitchenapp.core.domain.units.UnitCode
import kotlin.uuid.Uuid

/** Fixed scenario data for deterministic matching and shopping-list cases. */
object Slice8KitchenSimulation {
    private fun ingredient(id: String) = IngredientId(Uuid.parse(id))
    private fun recipeId(id: String) = RecipeId(Uuid.parse(id))

    val eggs = ingredient("00000000-0000-0000-0000-000000000081")
    val spinach = ingredient("00000000-0000-0000-0000-000000000082")
    val rice = ingredient("00000000-0000-0000-0000-000000000083")
    val peanuts = ingredient("00000000-0000-0000-0000-000000000084")

    private val location = KitchenLocation(name = "Simulation pantry")
    private val today = DomainLocalDate.of(2026, 11, 6)

    val skillet = Recipe(
        id = recipeId("00000000-0000-0000-0000-000000000801"),
        name = "Spinach and eggs",
        ingredients = listOf(
            RecipeIngredient(ingredientId = eggs, quantity = Rational.of(2), unit = UnitCode.EGG, rawLine = "2 eggs"),
            RecipeIngredient(ingredientId = spinach, rawLine = "spinach"),
        ),
        directionsText = "Cook in a skillet.",
        categories = listOf("Quick", "Breakfast"),
    )

    val riceBowl = Recipe(
        id = recipeId("00000000-0000-0000-0000-000000000802"),
        name = "Rice bowl",
        ingredients = listOf(
            RecipeIngredient(ingredientId = rice, quantity = Rational.of(2), unit = UnitCode.CUP, rawLine = "2 cups rice"),
        ),
        directionsText = "Cook rice and serve.",
        categories = listOf("Dinner"),
    )

    val excludedSatay = Recipe(
        id = recipeId("00000000-0000-0000-0000-000000000803"),
        name = "Peanut satay",
        ingredients = listOf(
            RecipeIngredient(ingredientId = peanuts, quantity = Rational.of(1), unit = UnitCode.CUP, rawLine = "1 cup peanuts"),
        ),
        directionsText = "Prepare the sauce.",
        categories = listOf("Dinner"),
    )

    val slowBreakfast = Recipe(
        id = recipeId("00000000-0000-0000-0000-000000000805"),
        name = "Slow breakfast",
        ingredients = listOf(
            RecipeIngredient(ingredientId = eggs, quantity = Rational.of(1), unit = UnitCode.EGG, rawLine = "1 egg"),
        ),
        directionsText = "Cook slowly.",
        categories = listOf("Breakfast"),
    )

    val input = MealMatchInput(
        recipes = listOf(skillet, riceBowl, excludedSatay, slowBreakfast),
        inventory = KitchenInventory(
            locations = listOf(location),
            lots = listOf(
                InventoryLot(
                    ingredientId = eggs,
                    locationId = location.id,
                    amount = InventoryAmount.Exact(Rational.of(2), UnitCode.EGG),
                ),
                InventoryLot(
                    ingredientId = spinach,
                    locationId = location.id,
                    amount = InventoryAmount.PresenceOnly,
                    userUseBy = today.plusDays(1),
                ),
                InventoryLot(
                    ingredientId = rice,
                    locationId = location.id,
                    amount = InventoryAmount.Exact(Rational.of(1), UnitCode.CUP),
                ),
            ),
        ),
        availableEquipment = setOf("Skillet"),
        householdRules = HouseholdIngredientRules(hardExclusions = setOf(peanuts)),
        query = MealQuery(
            mealTypes = setOf("breakfast", "dinner"),
            availableTimeMinutes = 30,
            servings = 2,
            requiredIngredients = setOf(eggs),
        ),
        metadata = mapOf(
            skillet.id to RecipeMatchMetadata(
                mealTypes = setOf("Breakfast"),
                estimatedMinutes = 15,
                servings = 2,
                equipmentRequirements = listOf(
                    EquipmentRequirement("Skillet", EquipmentRequirementKind.REQUIRED),
                    EquipmentRequirement("Blender", EquipmentRequirementKind.PREFERRED),
                ),
            ),
            riceBowl.id to RecipeMatchMetadata(
                mealTypes = setOf("Dinner"),
                estimatedMinutes = 25,
                servings = 4,
            ),
            excludedSatay.id to RecipeMatchMetadata(
                mealTypes = setOf("Dinner"),
                estimatedMinutes = 20,
                servings = 2,
                equipmentRequirements = listOf(
                    EquipmentRequirement("Oven", EquipmentRequirementKind.REQUIRED),
                ),
            ),
            slowBreakfast.id to RecipeMatchMetadata(
                mealTypes = setOf("Breakfast"),
                estimatedMinutes = 45,
                servings = 1,
            ),
        ),
        freshnessPolicies = mapOf(spinach to FreshnessPolicy(daysAfterPurchase = 5)),
        asOfDate = today,
        useSoonWindowDays = 2,
    )

    val expectedStatus = mapOf(
        skillet.name to CandidateStatus.INCLUDED,
        riceBowl.name to CandidateStatus.EXCLUDED,
        excludedSatay.name to CandidateStatus.EXCLUDED,
        slowBreakfast.name to CandidateStatus.EXCLUDED,
    )

    fun evaluate() = RecipeMatcher().match(input)

    fun shoppingLines() = ShoppingAggregator().aggregate(
        RecipeMatcher().match(input.copy(query = input.query.copy(requiredIngredients = emptySet()))),
    )
}
