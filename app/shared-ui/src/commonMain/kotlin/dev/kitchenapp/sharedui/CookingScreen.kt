package dev.kitchenapp.sharedui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kitchenapp.core.application.ConsumedInventory
import dev.kitchenapp.core.application.KitchenSnapshot
import dev.kitchenapp.core.application.PreparedLeftover
import dev.kitchenapp.core.domain.identifiers.RecipeIngredientId
import dev.kitchenapp.core.domain.recipe.Recipe

internal enum class LeftoverChoice { None, Now, Later }

@Composable
internal fun CookingScreen(
    recipe: Recipe,
    snapshot: KitchenSnapshot,
    checked: Set<RecipeIngredientId>,
    focusAllowed: Boolean,
    showReturnPrompt: Boolean,
    checkInMinutes: Int?,
    reminderMessage: String?,
    error: String?,
    busy: Boolean,
    onShowHistory: (() -> Unit)?,
    onCheckedChange: (RecipeIngredientId, Boolean) -> Unit,
    onResume: () -> Unit,
    onDismissPrompt: () -> Unit,
    onCheckInChange: (Int?) -> Unit,
    onComplete: (List<ConsumedInventory>, PreparedLeftover?, LeftoverChoice, Int?) -> Unit,
) {
    var closing by remember(recipe.id) { mutableStateOf(false) }
    var consumed by remember(recipe.id) { mutableStateOf<List<ConsumedInventory>?>(emptyList()) }
    var consumptionError by remember(recipe.id) { mutableStateOf<String?>(null) }
    var leftoverChoice by remember(recipe.id) { mutableStateOf(LeftoverChoice.None) }
    var leftover by remember(recipe.id) { mutableStateOf<PreparedLeftover?>(null) }
    var leftoverReminderMinutes by remember(recipe.id) { mutableStateOf<Int?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(recipe.name, style = MaterialTheme.typography.headlineSmall)
        if (!closing) {
            if (!focusAllowed) {
                Text("Cooking is still active. Resume to keep the screen awake.")
                OutlinedButton(onClick = onResume, enabled = !busy) { Text("Resume cooking") }
            }
            if (onShowHistory != null) {
                OutlinedButton(onClick = onShowHistory) { Text("Leftover follow-ups") }
            }
            CookingIngredients(recipe, checked, onCheckedChange)
            Text("Directions", style = MaterialTheme.typography.titleMedium)
            Text(recipe.directionsText.ifBlank { "No directions provided." })
            Text("Unfinished cook check-in", style = MaterialTheme.typography.titleMedium)
            Text("Optional reminder after you leave Kitchen. Delivery may be delayed.")
            listOf(null, 60, 120).forEach { minutes ->
                Row {
                    RadioButton(
                        selected = checkInMinutes == minutes,
                        onClick = { onCheckInChange(minutes) },
                        enabled = !busy,
                    )
                    Text(when (minutes) { 60 -> "After 1 hour"; 120 -> "After 2 hours"; else -> "Off" })
                }
            }
            reminderMessage?.let { Text(it) }
            Button(
                onClick = {
                    consumed = emptyList()
                    consumptionError = null
                    leftoverChoice = LeftoverChoice.None
                    leftover = null
                    leftoverReminderMinutes = null
                    closing = true
                },
                enabled = !busy,
            ) { Text("End Cook") }
        } else {
            Text("Review what you used. Nothing changes until you confirm.", style = MaterialTheme.typography.bodyLarge)
            ConsumptionEditor(snapshot) { value, message -> consumed = value; consumptionError = message }
            consumptionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("Leftovers", style = MaterialTheme.typography.titleMedium)
            LeftoverChoice.entries.forEach { choice ->
                Row {
                    RadioButton(selected = leftoverChoice == choice, onClick = { leftoverChoice = choice })
                    Text(when (choice) {
                        LeftoverChoice.None -> "No leftovers"
                        LeftoverChoice.Now -> "Record now"
                        LeftoverChoice.Later -> "Decide later"
                    })
                }
            }
            if (leftoverChoice == LeftoverChoice.Now) {
                LeftoverEditor(snapshot) { leftover = it }
                if (leftover == null) Text("Complete all required leftover fields.")
            }
            if (leftoverChoice == LeftoverChoice.Later) {
                Text("A pending follow-up remains available here even if notifications are unavailable.")
                listOf(null, 60, 120).forEach { minutes ->
                    Row {
                        RadioButton(
                            selected = leftoverReminderMinutes == minutes,
                            onClick = { leftoverReminderMinutes = minutes },
                        )
                        Text(when (minutes) { 60 -> "Remind me in 1 hour"; 120 -> "Remind me in 2 hours"; else -> "No reminder" })
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { closing = false }, enabled = !busy) { Text("Cancel") }
                Button(
                    enabled = !busy && consumed != null && (leftoverChoice != LeftoverChoice.Now || leftover != null),
                    onClick = {
                        onComplete(
                            requireNotNull(consumed),
                            leftover.takeIf { leftoverChoice == LeftoverChoice.Now },
                            leftoverChoice,
                            leftoverReminderMinutes.takeIf { leftoverChoice == LeftoverChoice.Later },
                        )
                    },
                ) { Text("Confirm End Cook") }
            }
        }
        if (!closing) error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }

    if (showReturnPrompt) {
        AlertDialog(
            onDismissRequest = onDismissPrompt,
            title = { Text("Still cooking?") },
            text = { Text("Your cook is still active. Continue to keep the screen awake.") },
            confirmButton = { TextButton(onClick = onResume) { Text("Continue") } },
            dismissButton = { TextButton(onClick = onDismissPrompt) { Text("Not now") } },
        )
    }
}
