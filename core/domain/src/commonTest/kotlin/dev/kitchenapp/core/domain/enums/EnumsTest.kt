package dev.kitchenapp.core.domain.enums

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnumsTest {

    @Test
    fun `TrackingMode fromValue round-trips and rejects unknown`() {
        for (mode in TrackingMode.entries) {
            assertEquals(mode, TrackingMode.fromValue(mode.value))
        }
        assertNull(TrackingMode.fromValue(-1))
        assertNull(TrackingMode.fromValue(TrackingMode.entries.size))
    }

    @Test
    fun `ApproximateLevel fromValue round-trips and rejects unknown`() {
        for (level in ApproximateLevel.entries) {
            assertEquals(level, ApproximateLevel.fromValue(level.value))
        }
        assertNull(ApproximateLevel.fromValue(-1))
        assertNull(ApproximateLevel.fromValue(ApproximateLevel.entries.size))
    }

    @Test
    fun `IngredientImportance fromValue round-trips and rejects unknown`() {
        for (importance in IngredientImportance.entries) {
            assertEquals(importance, IngredientImportance.fromValue(importance.value))
        }
        assertNull(IngredientImportance.fromValue(-1))
        assertNull(IngredientImportance.fromValue(IngredientImportance.entries.size))
    }

    @Test
    fun `RecipeReadiness fromValue round-trips and rejects unknown`() {
        for (readiness in RecipeReadiness.entries) {
            assertEquals(readiness, RecipeReadiness.fromValue(readiness.value))
        }
        assertNull(RecipeReadiness.fromValue(-1))
        assertNull(RecipeReadiness.fromValue(RecipeReadiness.entries.size))
    }

    @Test
    fun `NormalizationState fromValue round-trips and rejects unknown`() {
        for (state in NormalizationState.entries) {
            assertEquals(state, NormalizationState.fromValue(state.value))
        }
        assertNull(NormalizationState.fromValue(-1))
        assertNull(NormalizationState.fromValue(NormalizationState.entries.size))
    }

    @Test
    fun `enum values are distinct`() {
        assertEquals(TrackingMode.entries.size, TrackingMode.entries.toSet().size)
        assertEquals(ApproximateLevel.entries.size, ApproximateLevel.entries.toSet().size)
        assertEquals(IngredientImportance.entries.size, IngredientImportance.entries.toSet().size)
        assertEquals(RecipeReadiness.entries.size, RecipeReadiness.entries.toSet().size)
        assertEquals(NormalizationState.entries.size, NormalizationState.entries.toSet().size)
    }

    @Test
    fun `isRequired is true only for CRITICAL and IMPORTANT`() {
        assertTrue(IngredientImportance.CRITICAL.isRequired())
        assertTrue(IngredientImportance.IMPORTANT.isRequired())
        assertFalse(IngredientImportance.OPTIONAL.isRequired())
        assertFalse(IngredientImportance.SUBSTITUTABLE.isRequired())
    }

    @Test
    fun `isSubstitutable is true only for SUBSTITUTABLE`() {
        assertFalse(IngredientImportance.CRITICAL.isSubstitutable())
        assertFalse(IngredientImportance.IMPORTANT.isSubstitutable())
        assertFalse(IngredientImportance.OPTIONAL.isSubstitutable())
        assertTrue(IngredientImportance.SUBSTITUTABLE.isSubstitutable())
    }
}
