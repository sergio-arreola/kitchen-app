package dev.kitchenapp.data.importers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecipeTimeParserTest {
    @Test
    fun parsesSchemaDurations() {
        assertEquals(300L, parseRecipeTimeSeconds("PT5M"))
        assertEquals(5_400L, parseRecipeTimeSeconds("PT1H30M"))
        assertEquals(86_400L, parseRecipeTimeSeconds("P1D"))
    }

    @Test
    fun parsesHumanTimeLabels() {
        assertEquals(2_700L, parseRecipeTimeSeconds("45 minutes"))
        assertEquals(5_400L, parseRecipeTimeSeconds("1 hour 30 minutes"))
        assertEquals(5_400L, parseRecipeTimeSeconds("1 hour 30 minutes - preheat the oven"))
    }

    @Test
    fun leavesRangesAndInvalidValuesUnknown() {
        assertNull(parseRecipeTimeSeconds("15-20 minutes"))
        assertNull(parseRecipeTimeSeconds("P1M"))
        assertNull(parseRecipeTimeSeconds("sometime later"))
    }
}
