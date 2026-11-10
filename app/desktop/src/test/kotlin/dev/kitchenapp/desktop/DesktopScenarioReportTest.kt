package dev.kitchenapp.desktop

import dev.kitchenapp.core.engine.matching.CandidateStatus
import dev.kitchenapp.testkit.kitchen.Slice8KitchenSimulation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DesktopScenarioReportTest {
    @Test
    fun `scenario report preserves deterministic fixture outcomes and shopping gaps`() {
        val first = runDesktopScenario()
        val second = runDesktopScenario()

        assertEquals(first, second)
        assertEquals(
            Slice8KitchenSimulation.expectedStatus,
            first.matches.associate { it.recipe.name to it.status },
        )
        assertFalse(first.shoppingLines.isEmpty())
    }
}
