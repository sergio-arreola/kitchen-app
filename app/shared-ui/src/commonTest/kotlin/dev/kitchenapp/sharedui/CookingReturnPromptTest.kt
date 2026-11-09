package dev.kitchenapp.sharedui

import dev.kitchenapp.core.application.ActiveCookingSession
import dev.kitchenapp.core.application.FirstReturnState
import dev.kitchenapp.core.domain.identifiers.RecipeId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CookingReturnPromptTest {
    @Test
    fun pendingPromptIsReconstructedAfterRecreationUntilAnswered() {
        val pending = ActiveCookingSession(
            sessionId = "session",
            recipeId = RecipeId.generate(),
            servings = null,
            firstReturnState = FirstReturnState.PROMPTED,
            resumeRequired = true,
        )
        assertTrue(shouldShowCookingReturnPrompt(pending))
        assertFalse(shouldShowCookingReturnPrompt(pending.copy(isAway = true)))
        assertFalse(shouldShowCookingReturnPrompt(pending.copy(firstReturnState = FirstReturnState.DISMISSED)))
        assertFalse(shouldShowCookingReturnPrompt(pending.copy(firstReturnState = FirstReturnState.CONFIRMED)))
        assertFalse(shouldShowCookingReturnPrompt(null))
    }
}
