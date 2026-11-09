package dev.kitchenapp.sharedui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import dev.kitchenapp.core.application.RecipeLibraryUseCases

@Composable
fun KitchenApp(
    workflows: RecipeLibraryUseCases,
    onRequestPaprikaArchive: ((String?) -> Unit) -> Unit,
    cookingWorkflows: CookingWorkflows,
    cookingPlatform: CookingPlatform = NoCookingPlatform,
) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
    ) {
        KitchenAppNavigation(workflows, onRequestPaprikaArchive, cookingWorkflows, cookingPlatform)
    }
}
