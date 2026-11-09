package dev.kitchenapp.sharedui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kitchenapp.core.application.PreparedLeftover

@Composable
internal fun CookingHome(
    coordinator: CookingCoordinator,
    platform: CookingPlatform,
    requestedEventId: String?,
    onRequestHandled: () -> Unit,
    onBrowse: () -> Unit,
) {
    var editingEventId by remember { mutableStateOf<String?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    LaunchedEffect(coordinator) { coordinator.refresh() }
    LaunchedEffect(requestedEventId, coordinator.history) {
        if (requestedEventId != null) {
            if (coordinator.history.any { it.event.id == requestedEventId && it.leftoverFollowUpPending }) {
                editingEventId = requestedEventId
            }
            onRequestHandled()
        }
    }
    var leftover by remember(editingEventId) { mutableStateOf<PreparedLeftover?>(null) }
    val active = coordinator.active
    val recipe = coordinator.recipe

    DisposableEffect(active?.sessionId, coordinator.focusAllowed, editingEventId, showHistory) {
        platform.setCookingScreen(active != null && editingEventId == null && !showHistory, coordinator.focusAllowed)
        onDispose { platform.setCookingScreen(false, false) }
    }

    if (editingEventId != null) {
        val eventId = requireNotNull(editingEventId)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LeftoverEditor(coordinator.snapshot) { leftover = it }
            coordinator.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { editingEventId = null }) { Text("Cancel") }
                Button(enabled = leftover != null, onClick = {
                    coordinator.recordLeftover(eventId, requireNotNull(leftover))
                    if (coordinator.error == null) editingEventId = null
                }) { Text("Save leftovers") }
            }
        }
    } else if (active != null && recipe != null && !showHistory) {
        CookingScreen(
            recipe = recipe,
            snapshot = coordinator.snapshot,
            checked = active.checkedIngredients,
            focusAllowed = coordinator.focusAllowed,
            showReturnPrompt = coordinator.showPrompt,
            checkInMinutes = coordinator.checkInMinutes,
            reminderMessage = coordinator.message,
            error = coordinator.error,
            busy = false,
            onShowHistory = if (coordinator.history.any { it.leftoverFollowUpPending }) ({ showHistory = true }) else null,
            onCheckedChange = coordinator::toggle,
            onResume = coordinator::resume,
            onDismissPrompt = coordinator::dismissPrompt,
            onCheckInChange = coordinator::selectCheckIn,
            onComplete = coordinator::complete,
        )
    } else {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Cooking", style = MaterialTheme.typography.headlineSmall)
            if (showHistory && active != null) {
                OutlinedButton(onClick = { showHistory = false }) { Text("Back to cook") }
            } else if (active != null) {
                Text("The active recipe is unavailable. Your cooking session has been preserved.")
            } else {
                Text("Choose a saved recipe to start cooking.")
                Button(onClick = onBrowse) { Text("Browse recipes") }
            }
            coordinator.message?.let { Text(it) }
            coordinator.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val pending = coordinator.history.filter { it.leftoverFollowUpPending }
            if (pending.isNotEmpty()) {
                Text("Leftovers to review", style = MaterialTheme.typography.titleMedium)
                pending.forEach { entry ->
                    Text("Cooked on ${entry.event.cookedOn}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { editingEventId = entry.event.id }) { Text("Record leftovers") }
                        OutlinedButton(onClick = { coordinator.dismissLeftover(entry.event.id) }) { Text("No leftovers") }
                    }
                }
            }
        }
    }
}
