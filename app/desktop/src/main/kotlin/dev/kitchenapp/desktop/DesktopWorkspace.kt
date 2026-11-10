package dev.kitchenapp.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kitchenapp.core.application.RecipeLibraryUseCases
import dev.kitchenapp.sharedui.CookingPlatform
import dev.kitchenapp.sharedui.CookingWorkflows
import dev.kitchenapp.sharedui.KitchenApp

@Composable
internal fun DesktopWorkspace(
    workflows: RecipeLibraryUseCases,
    cookingWorkflows: CookingWorkflows,
    cookingPlatform: CookingPlatform,
    onRequestPaprikaArchive: ((String?) -> Unit) -> Unit,
) {
    var selectedWorkspace by remember { mutableStateOf(DesktopWorkspaceTab.Library) }

    MaterialTheme {
        Column {
            Surface(shadowElevation = 2.dp) {
                Column {
                    Text(
                        "Kitchen · Desktop engineering workspace",
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        DesktopWorkspaceTab.entries.forEach { tab ->
                            TextButton(onClick = { selectedWorkspace = tab }) {
                                Text(
                                    if (selectedWorkspace == tab) "• ${tab.label}" else tab.label,
                                    color = if (selectedWorkspace == tab) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }
                        }
                    }
                }
            }
            when (selectedWorkspace) {
                DesktopWorkspaceTab.Library -> KitchenApp(
                    workflows = workflows,
                    onRequestPaprikaArchive = onRequestPaprikaArchive,
                    cookingWorkflows = cookingWorkflows,
                    cookingPlatform = cookingPlatform,
                )
                DesktopWorkspaceTab.Scenarios -> DesktopScenarioScreen()
            }
        }
    }
}

private enum class DesktopWorkspaceTab(val label: String) {
    Library("Recipe library"),
    Scenarios("Kitchen scenarios"),
}
