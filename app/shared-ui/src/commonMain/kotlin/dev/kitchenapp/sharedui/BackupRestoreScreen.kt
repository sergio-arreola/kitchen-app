package dev.kitchenapp.sharedui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kitchenapp.core.application.BackupRestoreRecordCounts
import dev.kitchenapp.core.application.KitchenBackupRestorePreview

sealed interface BackupDocumentResult {
    data object ExportSucceeded : BackupDocumentResult
    class ImportSelected(val bytes: ByteArray) : BackupDocumentResult
    data object PickerCancelled : BackupDocumentResult
    data class Failed(val message: String) : BackupDocumentResult
}

internal sealed interface BackupRestoreScreenState {
    data object Empty : BackupRestoreScreenState

    data class Loading(
        val message: String,
    ) : BackupRestoreScreenState

    data class Review(
        val preview: KitchenBackupRestorePreview,
        val message: String? = null,
    ) : BackupRestoreScreenState

    data class Success(
        val message: String,
    ) : BackupRestoreScreenState

    data class Error(
        val message: String,
    ) : BackupRestoreScreenState
}

@Composable
internal fun BackupRestoreScreen(
    state: BackupRestoreScreenState,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onReplaceAll: () -> Unit,
    onCancelRestore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isLoading = state is BackupRestoreScreenState.Loading

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Backup and restore", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Export or restore your kitchen data on this device. These actions work offline.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !isLoading, onClick = onExport) {
                Text("Export backup")
            }
            OutlinedButton(enabled = !isLoading, onClick = onImport) {
                Text("Import backup")
            }
        }

        when (state) {
            BackupRestoreScreenState.Empty -> {
                Text(
                    "Choose Export backup to save your data, or Import backup to review a backup before restoring it.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            is BackupRestoreScreenState.Loading -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Text(state.message, style = MaterialTheme.typography.bodyLarge)
                }
            }

            is BackupRestoreScreenState.Review -> {
                val preview = state.preview
                Text("Restore preview", style = MaterialTheme.typography.titleLarge)
                state.message?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "No changes happen unless you choose Replace All. You can cancel at any time.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                CountSection("In this backup", preview.backupCounts)
                CountSection("Currently on this device", preview.currentCounts)

                preview.warnings.forEach { warning ->
                    Text(
                        text = "• $warning",
                        color = if (preview.hasActiveCookingSession) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                if (preview.hasActiveCookingSession) {
                    Text(
                        "Restore is blocked while cooking is active. No data has been changed.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                Button(
                    enabled = preview.canApply,
                    onClick = onReplaceAll,
                ) {
                    Text("Replace all local backup-managed data")
                }
                OutlinedButton(onClick = onCancelRestore) {
                    Text("Cancel restore")
                }
            }

            is BackupRestoreScreenState.Success -> {
                Text("Success", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                Text(state.message, style = MaterialTheme.typography.bodyLarge)
            }

            is BackupRestoreScreenState.Error -> {
                Text("Could not complete backup operation", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
                Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun CountSection(
    title: String,
    counts: BackupRestoreRecordCounts,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        CountRow("Recipes", counts.recipes)
        CountRow("Categories", counts.categories)
        CountRow("Canonical ingredients", counts.ingredients)
        CountRow("Locations", counts.locations)
        CountRow("Inventory lots", counts.inventoryLots)
        CountRow("Recipe images", counts.images)
        CountRow("Shopping-list items", counts.shoppingListItems)
        CountRow("Cooking events", counts.cookingEvents)
        CountRow("Leftovers", counts.leftovers)
    }
}

@Composable
private fun CountRow(
    label: String,
    count: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(count.toString(), style = MaterialTheme.typography.bodyMedium)
    }
}
