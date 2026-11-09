package dev.kitchenapp.sharedui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.kitchenapp.core.application.KitchenSnapshot
import dev.kitchenapp.core.application.PreparedLeftover
import dev.kitchenapp.core.domain.identifiers.IngredientId
import dev.kitchenapp.core.domain.identifiers.KitchenLocationId
import dev.kitchenapp.core.domain.time.DomainLocalDate
import dev.kitchenapp.core.domain.units.UnitCode
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

@Composable
internal fun LeftoverEditor(
    snapshot: KitchenSnapshot,
    onChange: (PreparedLeftover?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var ingredientId by remember { mutableStateOf<IngredientId?>(null) }
    var locationId by remember { mutableStateOf<KitchenLocationId?>(null) }
    var quantity by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf<UnitCode?>(null) }
    var unitMenu by remember { mutableStateOf(false) }
    var preparedOn by remember { mutableStateOf(DomainLocalDate.today(TimeZone.currentSystemDefault()).toString()) }
    var useBy by remember { mutableStateOf("") }

    fun report() {
        val parsed = runCatching {
            val amount = requireNotNull(parseCookingQuantity(quantity))
            val prepared = DomainLocalDate(LocalDate.parse(preparedOn.trim()))
            val expiry = useBy.trim().takeIf { it.isNotEmpty() }?.let { DomainLocalDate(LocalDate.parse(it)) }
            PreparedLeftover(
                ingredientId = requireNotNull(ingredientId),
                name = name.trim(),
                quantity = amount,
                unit = unit,
                locationId = requireNotNull(locationId),
                preparedOn = prepared,
                useBy = expiry,
            )
        }.getOrNull()
        onChange(parsed)
    }

    Text("Leftovers", style = MaterialTheme.typography.titleMedium)
    if (snapshot.ingredients.isEmpty() || snapshot.inventory.locations.isEmpty()) {
        Text("Add an ingredient and storage location in Kitchen before recording leftovers.")
    }
    OutlinedTextField(
        value = name,
        onValueChange = { name = it; report() },
        label = { Text("Leftover name") },
        modifier = Modifier.fillMaxWidth(),
    )
    Text("Inventory ingredient")
    snapshot.ingredients.forEach { ingredient ->
        Row {
            RadioButton(selected = ingredient.id == ingredientId, onClick = { ingredientId = ingredient.id; report() })
            Text(ingredient.name)
        }
    }
    Text("Storage location")
    snapshot.inventory.locations.forEach { location ->
        Row {
            RadioButton(selected = location.id == locationId, onClick = { locationId = location.id; report() })
            Text(location.name)
        }
    }
    OutlinedTextField(
        value = quantity,
        onValueChange = { quantity = it; report() },
        label = { Text("Quantity") },
        modifier = Modifier.fillMaxWidth(),
    )
    Column {
        TextButton(onClick = { unitMenu = true }) { Text(unit?.displayName ?: "No unit") }
        DropdownMenu(expanded = unitMenu, onDismissRequest = { unitMenu = false }) {
            (listOf(null) + UnitCode.entries.filter { it.dimension.name in setOf("MASS", "VOLUME", "COUNT") })
                .forEach { choice ->
                    DropdownMenuItem(
                        text = { Text(choice?.displayName ?: "No unit") },
                        onClick = { unit = choice; unitMenu = false; report() },
                    )
                }
        }
    }
    OutlinedTextField(
        value = preparedOn,
        onValueChange = { preparedOn = it; report() },
        label = { Text("Prepared date (YYYY-MM-DD)") },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = useBy,
        onValueChange = { useBy = it; report() },
        label = { Text("Use by (optional, YYYY-MM-DD)") },
        modifier = Modifier.fillMaxWidth(),
    )
}
