package dev.kitchenapp.sharedui

import dev.kitchenapp.core.application.ActiveCookingSession
import dev.kitchenapp.core.domain.identifiers.RecipeId
import kotlin.test.Test
import kotlin.test.assertEquals

class InitialAppRouteTest {
    @Test
    fun opensActiveCookAndOtherwiseOpensRecipes() {
        assertEquals(RecipesRoute, initialAppRoute(null))
        assertEquals(
            CookingRoute,
            initialAppRoute(ActiveCookingSession("session", RecipeId.generate(), null)),
        )
    }
}
