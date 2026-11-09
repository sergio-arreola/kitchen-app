package dev.kitchenapp.sharedui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.kitchenapp.core.application.KitchenSnapshot
import dev.kitchenapp.core.application.RecipeLibraryUseCases
import dev.kitchenapp.core.domain.enums.ApproximateLevel
import dev.kitchenapp.core.domain.household.HouseholdIngredientRuleKind
import dev.kitchenapp.core.domain.household.HouseholdIngredientRules
import dev.kitchenapp.core.domain.household.IngredientPreference
import dev.kitchenapp.core.domain.identifiers.IngredientId
import dev.kitchenapp.core.domain.inventory.InventoryAmount
import dev.kitchenapp.core.domain.inventory.InventoryLot
import dev.kitchenapp.core.domain.inventory.KitchenInventory
import dev.kitchenapp.core.domain.inventory.KitchenLocation
import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.core.domain.units.UnitCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun KitchenSetupScreen(workflows: RecipeLibraryUseCases) {
    var snapshot by remember { mutableStateOf<KitchenSnapshot?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableIntStateOf(0) }
    var showLocationManager by rememberSaveable { mutableStateOf(false) }
    var showLotEditor by rememberSaveable { mutableStateOf(false) }
    var editingLot by remember { mutableStateOf<InventoryLot?>(null) }
    var newEquipment by rememberSaveable { mutableStateOf("") }
    var newRuleIngredient by rememberSaveable { mutableStateOf("") }
    var selectedRuleKind by rememberSaveable { mutableStateOf(HouseholdIngredientRuleKind.AVOID) }
    var ruleKindMenuExpanded by remember { mutableStateOf(false) }
    var ingredientError by remember { mutableStateOf<String?>(null) }
    var configurationError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(retryKey) {
        loading = true
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val current = workflows.editInventory.get()
                if (current.inventory.locations.isNotEmpty()) {
                    current
                } else {
                    val initialized = current.copy(
                        inventory = KitchenInventory(
                            locations = listOf(
                                KitchenLocation(name = "Pantry"),
                                KitchenLocation(name = "Refrigerator"),
                                KitchenLocation(name = "Freezer"),
                            ),
                        ),
                    )
                    workflows.editInventory.update { initialized }
                }
            }
        }
        result.onSuccess {
            snapshot = it
            error = null
        }.onFailure {
            error = "Kitchen information could not be loaded."
        }
        loading = false
    }

    fun saveInventory(transform: (KitchenSnapshot) -> KitchenSnapshot) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { workflows.editInventory.update(transform) }
            }
            result.onSuccess {
                snapshot = it
                error = null
            }.onFailure {
                error = it.message ?: "Kitchen changes could not be saved."
            }
        }
    }

    fun saveRules(rules: HouseholdIngredientRules) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    workflows.configureKitchen.update(
                        equipment = requireNotNull(snapshot).availableEquipment,
                        rules = rules,
                    )
                }
            }
            result.onSuccess {
                snapshot = it
                configurationError = null
            }.onFailure {
                configurationError = it.message ?: "Kitchen settings could not be saved."
            }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Kitchen") }) }) { padding ->
        if (loading) {
            AppContentStatePanel(
                modifier = Modifier.padding(padding),
                state = AppContentState(
                    AppContentStateKind.Loading,
                    "Loading kitchen",
                    "Reading your local inventory and preferences.",
                ),
            )
        } else if (error != null && snapshot == null) {
            AppContentStatePanel(
                modifier = Modifier.padding(padding),
                state = AppContentState(
                    AppContentStateKind.Error,
                    "Kitchen unavailable",
                    requireNotNull(error),
                    actionLabel = "Retry",
                ),
                onAction = { retryKey++ },
            )
        } else {
            val current = snapshot
            if (current != null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (error != null) {
                        Text(requireNotNull(error), color = MaterialTheme.colorScheme.error)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = {
                            editingLot = null
                            showLotEditor = true
                        }) { Text("Add inventory") }
                        OutlinedButton(onClick = { showLocationManager = true }) {
                            Text("Locations")
                        }
                    }

                    Text("Storage locations", style = MaterialTheme.typography.titleMedium)
                    current.inventory.locations.inSetupDisplayOrder().forEach { location ->
                        Text(location.name, style = MaterialTheme.typography.bodyMedium)
                    }

                    HorizontalDivider()
                    Text("Inventory", style = MaterialTheme.typography.titleLarge)
                    if (current.inventory.lots.isEmpty()) {
                        Text("Your inventory is empty. Add ingredients and choose how precisely to track each amount.")
                    } else {
                        current.inventory.lots.forEach { lot ->
                            val ingredient = current.ingredients.firstOrNull { it.id == lot.ingredientId }
                            val location = current.inventory.locations.firstOrNull { it.id == lot.locationId }
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(ingredient?.name ?: "Unknown ingredient", style = MaterialTheme.typography.titleMedium)
                                    Text("${lot.amount.label()} · ${location?.name ?: "Unknown location"}")
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(onClick = {
                                            editingLot = lot
                                            showLotEditor = true
                                        }) { Text("Edit") }
                                        TextButton(onClick = {
                                            saveInventory { state ->
                                                state.copy(inventory = state.inventory.discardLot(lot.id))
                                            }
                                        }) { Text("Remove") }
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider()
                    Text("Ingredient catalog", style = MaterialTheme.typography.titleLarge)
                    Text("Ingredients used by recipes, stock, or rules must be unlinked before deletion.")
                    current.ingredients.sortedBy { it.name.lowercase() }.forEach { ingredient ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(ingredient.name, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching {
                                            check(workflows.deleteCanonicalIngredient.delete(ingredient.id)) {
                                                "Ingredient is no longer in the catalog"
                                            }
                                            workflows.editInventory.get()
                                        }
                                    }
                                    result.onSuccess {
                                        snapshot = it
                                        ingredientError = null
                                    }.onFailure {
                                        ingredientError = it.message ?: "Ingredient could not be deleted."
                                    }
                                }
                            }) { Text("Delete") }
                        }
                    }
                    ingredientError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                    HorizontalDivider()
                    Text("Equipment", style = MaterialTheme.typography.titleLarge)
                    Text("Add equipment available in your kitchen.")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            modifier = Modifier.weight(1f),
                            value = newEquipment,
                            onValueChange = { newEquipment = it; configurationError = null },
                            label = { Text("Equipment") },
                            singleLine = true,
                        )
                        Button(
                            enabled = newEquipment.isNotBlank(),
                            onClick = {
                                val item = newEquipment.trim()
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching {
                                            workflows.configureKitchen.update(
                                                equipment = requireNotNull(snapshot).availableEquipment + item,
                                                rules = requireNotNull(snapshot).householdRules,
                                            )
                                        }
                                    }
                                    result.onSuccess {
                                        snapshot = it
                                        newEquipment = ""
                                        configurationError = null
                                    }.onFailure {
                                        configurationError = it.message ?: "Equipment could not be saved."
                                    }
                                }
                            },
                        ) { Text("Add") }
                    }
                    current.availableEquipment.sortedBy(String::lowercase).forEach { equipment ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(equipment, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching {
                                            workflows.configureKitchen.update(
                                                equipment = requireNotNull(snapshot).availableEquipment - equipment,
                                                rules = requireNotNull(snapshot).householdRules,
                                            )
                                        }
                                    }
                                    result.onSuccess { snapshot = it }
                                        .onFailure { configurationError = it.message ?: "Equipment could not be removed." }
                                }
                            }) { Text("Remove") }
                        }
                    }

                    HorizontalDivider()
                    Text("Allergens and preferences", style = MaterialTheme.typography.titleLarge)
                    Text("Only rules added here affect recommendations. For V1 they apply household-wide. Allergens and Avoid are hard blocks; Dislike is a soft penalty.")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box {
                            OutlinedButton(onClick = { ruleKindMenuExpanded = true }) {
                                Text(selectedRuleKind.label())
                            }
                            DropdownMenu(
                                expanded = ruleKindMenuExpanded,
                                onDismissRequest = { ruleKindMenuExpanded = false },
                            ) {
                                HouseholdIngredientRuleKind.entries.forEach { kind ->
                                    DropdownMenuItem(
                                        text = { Text(kind.label()) },
                                        onClick = {
                                            selectedRuleKind = kind
                                            ruleKindMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                        OutlinedTextField(
                            modifier = Modifier.weight(1f),
                            value = newRuleIngredient,
                            onValueChange = { newRuleIngredient = it; configurationError = null },
                            label = { Text("Add rule for ingredient") },
                            singleLine = true,
                        )
                        Button(
                            enabled = newRuleIngredient.isNotBlank(),
                            onClick = {
                                val name = newRuleIngredient.trim()
                                val kind = selectedRuleKind
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching {
                                            val ingredient = workflows.addCanonicalIngredient.add(name)
                                            val state = workflows.editInventory.get()
                                            workflows.configureKitchen.update(
                                                equipment = state.availableEquipment,
                                                rules = state.householdRules.withRule(ingredient.id, kind),
                                            )
                                        }
                                    }
                                    result.onSuccess {
                                        snapshot = it
                                        newRuleIngredient = ""
                                        configurationError = null
                                    }.onFailure {
                                        configurationError = it.message ?: "Ingredient rule could not be saved."
                                    }
                                }
                            },
                        ) { Text("Add") }
                    }
                    val ruleIngredientIds = current.householdRules.allergens +
                        current.householdRules.hardExclusions +
                        current.householdRules.preferences.map { it.ingredientId }
                    current.ingredients
                        .filter { it.id in ruleIngredientIds }
                        .sortedBy { it.name.lowercase() }
                        .forEach { ingredient ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(ingredient.name, modifier = Modifier.weight(1f))
                                Text(current.householdRules.ruleKind(ingredient.id).label())
                                TextButton(onClick = {
                                    saveRules(current.householdRules.withoutRule(ingredient.id))
                                }) { Text("Delete") }
                            }
                            HorizontalDivider()
                        }
                    configurationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }

    val current = snapshot
    if (showLocationManager && current != null) {
        LocationManagerDialog(
            locations = current.inventory.locations.inSetupDisplayOrder(),
            lots = current.inventory.lots,
            onDismiss = { showLocationManager = false },
            onAdd = { name ->
                saveInventory { state ->
                    state.copy(inventory = state.inventory.addLocation(KitchenLocation(name = name)))
                }
            },
            onRename = { id, name ->
                saveInventory { state ->
                    state.copy(inventory = state.inventory.renameLocation(id, name))
                }
            },
            onRemove = { id ->
                saveInventory { state ->
                    state.copy(inventory = state.inventory.removeLocation(id))
                }
            },
        )
    }
    if (showLotEditor && current != null) {
        InventoryLotDialog(
            lot = editingLot,
            ingredients = current.ingredients,
            locations = current.inventory.locations.inSetupDisplayOrder(),
            onDismiss = { showLotEditor = false },
            onSave = { ingredientName, locationId, amount ->
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val ingredient = workflows.addCanonicalIngredient.add(ingredientName)
                            workflows.editInventory.update { state ->
                                val lot = editingLot?.copy(
                                    ingredientId = ingredient.id,
                                    locationId = locationId,
                                    amount = amount,
                                ) ?: InventoryLot(
                                    ingredientId = ingredient.id,
                                    locationId = locationId,
                                    amount = amount,
                                )
                                val inventory = if (editingLot == null) {
                                    state.inventory.addLot(lot)
                                } else {
                                    state.inventory.updateLot(lot)
                                }
                                state.copy(inventory = inventory)
                            }
                        }
                    }
                    result.onSuccess {
                        snapshot = it
                        showLotEditor = false
                        error = null
                    }.onFailure {
                        error = it.message ?: "Inventory item could not be saved."
                    }
                }
            },
        )
    }
}

@Composable
private fun LocationManagerDialog(
    locations: List<KitchenLocation>,
    lots: List<InventoryLot>,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
    onRename: (dev.kitchenapp.core.domain.identifiers.KitchenLocationId, String) -> Unit,
    onRemove: (dev.kitchenapp.core.domain.identifiers.KitchenLocationId) -> Unit,
) {
    var newName by rememberSaveable { mutableStateOf("") }
    var editingId by remember { mutableStateOf<dev.kitchenapp.core.domain.identifiers.KitchenLocationId?>(null) }
    var editingName by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Storage locations") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it; error = null },
                    label = { Text("Add custom location") },
                    singleLine = true,
                )
                Button(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        runCatching { onAdd(newName) }
                            .onSuccess { newName = ""; error = null }
                            .onFailure { error = it.message ?: "Location could not be added." }
                    },
                ) { Text("Add location") }
                locations.forEach { location ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(location.name, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            editingId = location.id
                            editingName = location.name
                        }) { Text("Rename") }
                        TextButton(
                            enabled = lots.none { it.locationId == location.id },
                            onClick = {
                                runCatching { onRemove(location.id) }
                                    .onFailure { error = it.message ?: "Move or remove stock first." }
                            },
                        ) { Text("Remove") }
                    }
                    if (editingId == location.id) {
                        OutlinedTextField(
                            value = editingName,
                            onValueChange = { editingName = it; error = null },
                            label = { Text("New name") },
                            singleLine = true,
                        )
                        TextButton(onClick = {
                            runCatching { onRename(location.id, editingName) }
                                .onSuccess { editingId = null; error = null }
                                .onFailure { error = it.message ?: "Location could not be renamed." }
                        }) { Text("Save name") }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun InventoryLotDialog(
    lot: InventoryLot?,
    ingredients: List<dev.kitchenapp.core.domain.ingredient.Ingredient>,
    locations: List<KitchenLocation>,
    onDismiss: () -> Unit,
    onSave: (String, dev.kitchenapp.core.domain.identifiers.KitchenLocationId, InventoryAmount) -> Unit,
) {
    var ingredientName by rememberSaveable(lot?.id?.toString()) {
        mutableStateOf(lot?.let { value -> ingredients.firstOrNull { it.id == value.ingredientId }?.name }.orEmpty())
    }
    var selectedLocationId by remember(lot?.id) {
        mutableStateOf(lot?.locationId ?: locations.first().id)
    }
    var tracking by rememberSaveable(lot?.id?.toString()) {
        mutableStateOf(
            when (lot?.amount) {
                is InventoryAmount.Exact -> InventoryTracking.Exact
                is InventoryAmount.Approximate -> InventoryTracking.Approximate
                else -> InventoryTracking.Presence
            },
        )
    }
    var quantity by rememberSaveable(lot?.id?.toString()) {
        mutableStateOf(lot?.amount?.quantityText().orEmpty())
    }
    var selectedUnit by remember(lot?.id) {
        mutableStateOf(lot?.amount?.unitOrNull())
    }
    var unitMenu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (lot == null) "Add inventory" else "Edit inventory") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = ingredientName,
                    onValueChange = { ingredientName = it; error = null },
                    label = { Text("Ingredient") },
                    singleLine = true,
                )
                Text("Location", style = MaterialTheme.typography.labelLarge)
                Column {
                    locations.forEach { location ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = selectedLocationId == location.id,
                                onClick = { selectedLocationId = location.id },
                            )
                            Text(location.name)
                        }
                    }
                }
                Text("Tracking precision", style = MaterialTheme.typography.labelLarge)
                InventoryTracking.entries.forEach { option ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = tracking == option, onClick = { tracking = option })
                        Text(option.label)
                    }
                }
                if (tracking != InventoryTracking.Presence) {
                    OutlinedTextField(
                        value = quantity,
                        onValueChange = { quantity = it; error = null },
                        label = { Text("Amount") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(selectedUnit?.displayName ?: "No unit")
                        TextButton(onClick = { unitMenu = true }) { Text("Choose unit") }
                        DropdownMenu(expanded = unitMenu, onDismissRequest = { unitMenu = false }) {
                            (listOf(null) + UnitCode.entries.filter {
                                it.dimension.name in setOf("MASS", "VOLUME", "COUNT")
                            }).forEach { unit ->
                                DropdownMenuItem(
                                    text = { Text(unit?.displayName ?: "No unit") },
                                    onClick = { selectedUnit = unit; unitMenu = false },
                                )
                            }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = ingredientName.isNotBlank() && (tracking == InventoryTracking.Presence || quantity.isNotBlank()),
                onClick = {
                    val amount = if (tracking == InventoryTracking.Presence) {
                        InventoryAmount.PresenceOnly
                    } else {
                        val parsed = parseNonNegativeRational(quantity)
                        if (parsed == null) {
                            error = "Enter a non-negative amount."
                            return@Button
                        }
                        when (tracking) {
                            InventoryTracking.Presence -> InventoryAmount.PresenceOnly
                            InventoryTracking.Approximate -> InventoryAmount.Approximate(
                                parsed,
                                selectedUnit,
                                ApproximateLevel.MODERATE,
                            )
                            InventoryTracking.Exact -> InventoryAmount.Exact(parsed, selectedUnit)
                        }
                    }
                    onSave(ingredientName.trim(), selectedLocationId, amount)
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun HouseholdIngredientRules.withRule(
    ingredientId: IngredientId,
    kind: HouseholdIngredientRuleKind,
): HouseholdIngredientRules = when (kind) {
    HouseholdIngredientRuleKind.ALLERGEN -> copy(
        allergens = allergens + ingredientId,
        hardExclusions = hardExclusions - ingredientId,
        preferences = preferences.filterNot { it.ingredientId == ingredientId },
    )
    HouseholdIngredientRuleKind.AVOID -> copy(
        allergens = allergens - ingredientId,
        hardExclusions = hardExclusions + ingredientId,
        preferences = preferences.filterNot { it.ingredientId == ingredientId },
    )
    HouseholdIngredientRuleKind.DISLIKE -> copy(
        allergens = allergens - ingredientId,
        hardExclusions = hardExclusions - ingredientId,
        preferences = preferences.filterNot { it.ingredientId == ingredientId } +
            IngredientPreference(ingredientId),
    )
}

private fun HouseholdIngredientRules.withoutRule(ingredientId: IngredientId): HouseholdIngredientRules =
    copy(
        allergens = allergens - ingredientId,
        hardExclusions = hardExclusions - ingredientId,
        preferences = preferences.filterNot { it.ingredientId == ingredientId },
    )

private fun HouseholdIngredientRules.ruleKind(ingredientId: IngredientId): HouseholdIngredientRuleKind =
    when {
        ingredientId in allergens -> HouseholdIngredientRuleKind.ALLERGEN
        ingredientId in hardExclusions -> HouseholdIngredientRuleKind.AVOID
        else -> HouseholdIngredientRuleKind.DISLIKE
    }

private fun HouseholdIngredientRuleKind.label(): String = when (this) {
    HouseholdIngredientRuleKind.ALLERGEN -> "Allergen"
    HouseholdIngredientRuleKind.AVOID -> "Avoid"
    HouseholdIngredientRuleKind.DISLIKE -> "Dislike"
}

private fun List<KitchenLocation>.inSetupDisplayOrder(): List<KitchenLocation> =
    sortedBy { location ->
        when (location.name.lowercase()) {
            "pantry" -> 0
            "refrigerator" -> 1
            "freezer" -> 2
            else -> 3
        }
    }

private enum class InventoryTracking(val label: String) {
    Presence("Presence only"),
    Approximate("Approximate amount"),
    Exact("Exact amount"),
}

private fun InventoryAmount.label(): String = when (this) {
    InventoryAmount.PresenceOnly -> "Amount not tracked"
    is InventoryAmount.Approximate -> "About ${quantity.display()} ${unit?.symbol.orEmpty()}".trim()
    is InventoryAmount.Exact -> "${quantity.display()} ${unit?.symbol.orEmpty()}".trim()
}

private fun InventoryAmount.quantityText(): String = when (this) {
    InventoryAmount.PresenceOnly -> ""
    is InventoryAmount.Approximate -> quantity.display()
    is InventoryAmount.Exact -> quantity.display()
}

private fun InventoryAmount.unitOrNull(): UnitCode? = when (this) {
    InventoryAmount.PresenceOnly -> null
    is InventoryAmount.Approximate -> unit
    is InventoryAmount.Exact -> unit
}

private fun Rational.display(): String =
    if (denominator == 1L) numerator.toString() else toDouble().toString()

private fun parseNonNegativeRational(value: String): Rational? = runCatching {
    val normalized = value.trim()
    require(normalized.isNotEmpty())
    if ('/' in normalized) {
        val (numerator, denominator) = normalized.split('/', limit = 2)
        val parsedNumerator = numerator.toLong()
        val parsedDenominator = denominator.toLong()
        require(parsedNumerator >= 0 && parsedDenominator > 0)
        Rational.of(parsedNumerator, parsedDenominator)
    } else if ('.' in normalized) {
        val (whole, fraction) = normalized.split('.', limit = 2)
        require(fraction.isNotEmpty() && fraction.length <= 6)
        val numerator = (whole.ifEmpty { "0" } + fraction).toLong()
        val denominator = buildString { repeat(fraction.length) { append('0') } }.toLong()
        require(numerator >= 0)
        Rational.of(numerator, denominator)
    } else {
        val numerator = normalized.toLong()
        require(numerator >= 0)
        Rational.of(numerator)
    }
}.getOrNull()
