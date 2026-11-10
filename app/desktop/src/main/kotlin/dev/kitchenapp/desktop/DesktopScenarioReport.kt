package dev.kitchenapp.desktop

import dev.kitchenapp.core.engine.matching.RecipeMatch
import dev.kitchenapp.core.engine.matching.ShoppingLine
import dev.kitchenapp.testkit.kitchen.Slice8KitchenSimulation

internal data class DesktopScenarioReport(
    val matches: List<RecipeMatch>,
    val shoppingLines: List<ShoppingLine>,
)

internal fun runDesktopScenario(): DesktopScenarioReport = DesktopScenarioReport(
    matches = Slice8KitchenSimulation.evaluate(),
    shoppingLines = Slice8KitchenSimulation.shoppingLines(),
)
