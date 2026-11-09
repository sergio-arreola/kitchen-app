package dev.kitchenapp.sharedui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.kitchenapp.core.application.ConsumedInventory
import dev.kitchenapp.core.application.KitchenSnapshot
import dev.kitchenapp.core.domain.identifiers.RecipeIngredientId
import dev.kitchenapp.core.domain.inventory.InventoryAmount
import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.core.domain.recipe.Recipe

@Composable
internal fun CookingIngredients(
    recipe: Recipe,
    checked: Set<RecipeIngredientId>,
    onCheckedChange: (RecipeIngredientId, Boolean) -> Unit,
) {
    Text("Ingredients", style = MaterialTheme.typography.titleMedium)
    recipe.ingredients.forEach { ingredient ->
        val isChecked = ingredient.id in checked
        Row(modifier = Modifier.fillMaxWidth()) {
            Checkbox(checked = isChecked, onCheckedChange = { onCheckedChange(ingredient.id, it) })
            Text(
                text = ingredient.rawLine,
                modifier = Modifier.weight(1f),
                textDecoration = if (isChecked) TextDecoration.LineThrough else TextDecoration.None,
            )
        }
    }
}

@Composable
internal fun ConsumptionEditor(
    snapshot: KitchenSnapshot,
    onChange: (List<ConsumedInventory>?, String?) -> Unit,
) {
    val amounts = remember(snapshot) { mutableStateMapOf<String, String>() }
    val wholeLots = remember(snapshot) { mutableStateMapOf<String, Boolean>() }

    fun report() {
        val result = mutableListOf<ConsumedInventory>()
        for (lot in snapshot.inventory.lots) {
            when (val amount = lot.amount) {
                InventoryAmount.PresenceOnly -> if (wholeLots[lot.id.toString()] == true) {
                    result += ConsumedInventory(lot.id)
                }
                is InventoryAmount.Approximate, is InventoryAmount.Exact -> {
                    val value = amounts[lot.id.toString()].orEmpty().trim()
                    if (value.isNotEmpty()) {
                        val quantity = parseCookingQuantity(value)
                        if (quantity == null || !quantity.isPositive()) {
                            onChange(null, "Enter a positive quantity or leave the amount blank.")
                            return
                        }
                        val unit = when (amount) {
                            is InventoryAmount.Approximate -> amount.unit
                            is InventoryAmount.Exact -> amount.unit
                            else -> null
                        }
                        result += ConsumedInventory(lot.id, quantity, unit)
                    }
                }
            }
        }
        onChange(result, null)
    }

    Text("Actual consumption", style = MaterialTheme.typography.titleMedium)
    Text("Leave an amount blank if none was used. Quantities use the unit shown for each lot.")
    if (snapshot.inventory.lots.isEmpty()) Text("No tracked lots.")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        snapshot.inventory.lots.forEach { lot ->
            val name = snapshot.ingredients.firstOrNull { it.id == lot.ingredientId }?.name ?: "Ingredient"
            val location = snapshot.inventory.locations.firstOrNull { it.id == lot.locationId }?.name.orEmpty()
            val key = lot.id.toString()
            when (val amount = lot.amount) {
                InventoryAmount.PresenceOnly -> Row {
                    Checkbox(checked = wholeLots[key] == true, onCheckedChange = {
                        wholeLots[key] = it
                        report()
                    })
                    Text("$name · $location (consume whole lot)")
                }
                is InventoryAmount.Approximate, is InventoryAmount.Exact -> {
                    val quantity = when (amount) {
                        is InventoryAmount.Approximate -> amount.quantity
                        is InventoryAmount.Exact -> amount.quantity
                        else -> error("Unreachable")
                    }
                    val unit = when (amount) {
                        is InventoryAmount.Approximate -> amount.unit
                        is InventoryAmount.Exact -> amount.unit
                        else -> null
                    }
                    OutlinedTextField(
                        value = amounts[key].orEmpty(),
                        onValueChange = { amounts[key] = it; report() },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("$name · $location") },
                        supportingText = {
                            Text("Available: ${quantity.numerator}/${quantity.denominator} ${unit?.symbol.orEmpty()}")
                        },
                        singleLine = true,
                    )
                }
            }
        }
    }
}

internal fun parseCookingQuantity(input: String): Rational? = runCatching {
    val value = input.trim()
    require(value.isNotEmpty())
    when {
        '/' in value -> {
            val parts = value.split('/')
            require(parts.size == 2)
            Rational.of(parts[0].toLong(), parts[1].toLong())
        }
        '.' in value -> {
            val parts = value.split('.')
            require(parts.size == 2 && parts[1].isNotEmpty() && parts[1].length <= 6)
            val whole = parts[0].ifEmpty { "0" }
            require(whole.all(Char::isDigit) && parts[1].all(Char::isDigit))
            Rational.of((whole + parts[1]).toLong(), ("1" + "0".repeat(parts[1].length)).toLong())
        }
        else -> Rational.of(value.toLong())
    }
}.getOrNull()?.takeIf { it.isPositive() }
