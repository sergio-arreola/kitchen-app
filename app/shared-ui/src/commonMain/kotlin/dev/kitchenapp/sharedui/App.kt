package dev.kitchenapp.sharedui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import dev.kitchenapp.core.application.KitchenBackupWorkflows
import dev.kitchenapp.core.application.RecipeLibraryUseCases

@Composable
fun KitchenApp(
    workflows: RecipeLibraryUseCases,
    onRequestPaprikaArchive: ((String?) -> Unit) -> Unit,
    cookingWorkflows: CookingWorkflows,
    cookingPlatform: CookingPlatform = NoCookingPlatform,
    backupWorkflows: KitchenBackupWorkflows? = null,
    backupDocumentResult: BackupDocumentResult? = null,
    onConsumeBackupDocumentResult: () -> Unit = {},
    onRequestBackupExport: (ByteArray) -> Unit = {},
    onRequestBackupImport: () -> Unit = {},
) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
    ) {
        KitchenAppNavigation(
            workflows = workflows,
            onRequestPaprikaArchive = onRequestPaprikaArchive,
            cookingWorkflows = cookingWorkflows,
            cookingPlatform = cookingPlatform,
            backupWorkflows = backupWorkflows,
            backupDocumentResult = backupDocumentResult,
            onConsumeBackupDocumentResult = onConsumeBackupDocumentResult,
            onRequestBackupExport = onRequestBackupExport,
            onRequestBackupImport = onRequestBackupImport,
        )
    }
}
