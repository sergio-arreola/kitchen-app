package dev.kitchenapp.core.domain.recipe

import dev.kitchenapp.core.domain.household.EquipmentRequirement
import dev.kitchenapp.core.domain.household.EquipmentRequirementKind
import kotlin.test.Test
import kotlin.test.assertFailsWith

class RecipeRecommendationMetadataTest {
    @Test
    fun `meal types must be nonblank and unique ignoring case and whitespace`() {
        assertFailsWith<IllegalArgumentException> {
            recipe(mealTypes = listOf("Dinner", " dinner "))
        }
        assertFailsWith<IllegalArgumentException> {
            recipe(mealTypes = listOf(" "))
        }
    }

    @Test
    fun `equipment requirements must be unique ignoring case and whitespace`() {
        assertFailsWith<IllegalArgumentException> {
            recipe(
                equipmentRequirements = listOf(
                    EquipmentRequirement("Dutch oven", EquipmentRequirementKind.REQUIRED),
                    EquipmentRequirement(" dutch OVEN ", EquipmentRequirementKind.PREFERRED),
                ),
            )
        }
    }

    private fun recipe(
        mealTypes: List<String> = emptyList(),
        equipmentRequirements: List<EquipmentRequirement> = emptyList(),
    ) = Recipe(
        name = "Tomato stew",
        ingredients = emptyList(),
        directionsText = "Simmer.",
        mealTypes = mealTypes,
        equipmentRequirements = equipmentRequirements,
    )
}
