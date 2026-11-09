package dev.kitchenapp.sharedui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import dev.kitchenapp.core.application.RecipeLibraryUseCases
import dev.kitchenapp.core.application.SuggestionOptions
import dev.kitchenapp.core.domain.identifiers.IngredientId
import dev.kitchenapp.core.domain.identifiers.RecipeId
import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.core.domain.quantity.QuantityStatus
import dev.kitchenapp.core.domain.quantity.ReadinessStatus
import dev.kitchenapp.core.domain.shopping.ShoppingList
import dev.kitchenapp.core.domain.shopping.ShoppingListItem
import dev.kitchenapp.core.domain.time.DomainLocalDate
import dev.kitchenapp.core.engine.matching.CandidateStatus
import dev.kitchenapp.core.engine.matching.FreshnessStatus
import dev.kitchenapp.core.engine.matching.MealQuery
import dev.kitchenapp.core.engine.matching.MatchExclusion
import dev.kitchenapp.core.engine.matching.RecipeMatch
import dev.kitchenapp.core.engine.matching.RecipeMatchMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone

private data class RecommendationResults(
    val ingredients: List<Pair<IngredientId, String>>,
    val categories: List<String>,
    val mealTypes: List<String>,
    val hasRecipes: Boolean,
    val matches: List<RecipeMatch>,
)

@Composable
internal fun RecommendationScreen(
    workflows: RecipeLibraryUseCases,
    onOpenShoppingList: () -> Unit,
) {
    var availableTime by rememberSaveable { mutableStateOf("") }
    var servings by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf("") }
    var selectedMealTypes by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedIngredientIds by remember { mutableStateOf<Set<IngredientId>>(emptySet()) }
    var ingredients by remember { mutableStateOf(emptyList<Pair<IngredientId, String>>()) }
    var categories by remember { mutableStateOf(emptyList<String>()) }
    var mealTypeOptions by remember { mutableStateOf(emptyList<String>()) }
    var hasRecipes by remember { mutableStateOf(false) }
    var matches by remember { mutableStateOf(emptyList<RecipeMatch>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var filterError by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableIntStateOf(0) }
    var categoryMenuExpanded by remember { mutableStateOf(false) }
    var ingredientMenuExpanded by remember { mutableStateOf(false) }
    var mealTypeMenuExpanded by remember { mutableStateOf(false) }
    var creatingList by remember { mutableStateOf(false) }
    var showReplaceConfirmation by remember { mutableStateOf(false) }
    var pendingShoppingRecipes by remember { mutableStateOf<List<RecipeMatch>?>(null) }
    var selectedRecipeIds by remember { mutableStateOf<Set<RecipeId>>(emptySet()) }
    var actionError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun saveSelectedRecipesAsShoppingList(selected: List<RecipeMatch>) {
        creatingList = true
        actionError = null
        try {
            val result = withContext(Dispatchers.IO) {
                runSafely { workflows.createShoppingList.create(selected) }
            }
            result.onSuccess { onOpenShoppingList() }
                .onFailure { actionError = it.message ?: "Shopping list could not be created." }
        } finally {
            creatingList = false
        }
    }

    LaunchedEffect(availableTime, servings, selectedCategory, selectedMealTypes, selectedIngredientIds, retryKey) {
        val timeValue = availableTime.trim().takeIf(String::isNotEmpty)?.toIntOrNull()
        val servingsValue = servings.trim().takeIf(String::isNotEmpty)?.toIntOrNull()
        filterError = when {
            availableTime.isNotBlank() && (timeValue == null || timeValue < 0) ->
                "Enter available time as a whole number of minutes."
            servings.isNotBlank() && (servingsValue == null || servingsValue <= 0) ->
                "Enter servings as a positive whole number."
            else -> null
        }
        if (filterError != null) {
            loading = false
            matches = emptyList()
            selectedRecipeIds = emptySet()
            return@LaunchedEffect
        }

        loading = true
        val result = withContext(Dispatchers.IO) {
            runSafely {
                val recipes = workflows.searchRecipes.search("")
                val kitchen = workflows.editInventory.get()
                val newCategories = workflows.listRecipeCategories.getAll()
                val newMealTypes = recipes.flatMap { it.mealTypes }
                    .distinctBy(String::lowercase)
                    .sortedBy(String::lowercase)
                val ingredientOptions = kitchen.ingredients
                    .sortedBy { it.name.lowercase() }
                    .map { it.id to it.name }
                val metadata = recipes.associate { recipe ->
                    val totalSeconds = recipe.totalTimeSeconds ?: run {
                        val prep = recipe.prepTimeSeconds
                        val cook = recipe.cookTimeSeconds
                        if (prep != null && cook != null) {
                            prep + cook + (recipe.chillingTimeSeconds ?: 0)
                        } else {
                            null
                        }
                    }
                    val estimatedMinutes = totalSeconds?.let { seconds -> ((seconds + 59) / 60).toInt() }
                    recipe.id to RecipeMatchMetadata(
                        mealTypes = recipe.mealTypes.toSet(),
                        estimatedMinutes = estimatedMinutes,
                        servings = recipe.servings?.let(::parseServings),
                        equipmentRequirements = recipe.equipmentRequirements,
                    )
                }
                val query = MealQuery(
                    mealTypes = selectedMealTypes,
                    availableTimeMinutes = timeValue,
                    servings = servingsValue,
                    categories = selectedCategory.takeIf(String::isNotBlank)?.let(::setOf).orEmpty(),
                    requiredIngredients = selectedIngredientIds,
                )
                val suggestions = workflows.getSuggestions.get(
                    SuggestionOptions(
                        query = query,
                        metadata = metadata,
                        asOfDate = DomainLocalDate.today(TimeZone.currentSystemDefault()),
                    ),
                )
                RecommendationResults(
                    ingredients = ingredientOptions,
                    categories = newCategories,
                    mealTypes = newMealTypes,
                    hasRecipes = recipes.isNotEmpty(),
                    matches = suggestions,
                )
            }
        }
        result.onSuccess { recommendationData ->
            ingredients = recommendationData.ingredients
            categories = recommendationData.categories
            mealTypeOptions = recommendationData.mealTypes
            selectedMealTypes = selectedMealTypes.intersect(recommendationData.mealTypes.toSet())
            hasRecipes = recommendationData.hasRecipes
            matches = recommendationData.matches
            selectedRecipeIds = selectedRecipeIds.intersect(
                matches.filter { it.status == CandidateStatus.INCLUDED }.map { it.recipe.id }.toSet(),
            )
            error = null
        }.onFailure {
            error = "Recommendations could not be loaded from this device."
        }
        loading = false
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Recommendations") }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).imePadding(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Filter recipes using your saved recipes and kitchen state. Results work offline.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        modifier = Modifier.weight(1f),
                        value = availableTime,
                        onValueChange = { availableTime = it },
                        label = { Text("Available minutes") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        modifier = Modifier.weight(1f),
                        value = servings,
                        onValueChange = { servings = it },
                        label = { Text("Servings") },
                        singleLine = true,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f)) {
                        TextButton(onClick = { categoryMenuExpanded = true }) {
                            Text(if (selectedCategory.isBlank()) "Any category" else selectedCategory)
                        }
                        DropdownMenu(
                            expanded = categoryMenuExpanded,
                            onDismissRequest = { categoryMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Any category") },
                                onClick = { selectedCategory = ""; categoryMenuExpanded = false },
                            )
                            categories.forEach { category ->
                                DropdownMenuItem(
                                    text = { Text(category) },
                                    onClick = { selectedCategory = category; categoryMenuExpanded = false },
                                )
                            }
                        }
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        TextButton(onClick = { mealTypeMenuExpanded = true }) {
                            Text(
                                when (selectedMealTypes.size) {
                                    0 -> "Any meal type"
                                    1 -> "1 meal type"
                                    else -> "${selectedMealTypes.size} meal types"
                                },
                            )
                        }
                        DropdownMenu(
                            expanded = mealTypeMenuExpanded,
                            onDismissRequest = { mealTypeMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Any meal type") },
                                onClick = { selectedMealTypes = emptySet(); mealTypeMenuExpanded = false },
                            )
                            mealTypeOptions.forEach { mealType ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        selectedMealTypes = selectedMealTypes.toggleValue(mealType)
                                    }.padding(horizontal = 12.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = mealType in selectedMealTypes,
                                        onCheckedChange = { selectedMealTypes = selectedMealTypes.toggleValue(mealType) },
                                    )
                                    Text(mealType)
                                }
                            }
                            if (mealTypeOptions.isEmpty()) {
                                Text("Add meal types while reviewing a recipe.", modifier = Modifier.padding(12.dp))
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f)) {
                        TextButton(onClick = { ingredientMenuExpanded = true }) {
                            Text(if (selectedIngredientIds.isEmpty()) "Any ingredients" else "${selectedIngredientIds.size} required")
                        }
                        DropdownMenu(
                            expanded = ingredientMenuExpanded,
                            onDismissRequest = { ingredientMenuExpanded = false },
                        ) {
                            Column(
                                modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                            ) {
                                ingredients.forEach { (id, name) ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            selectedIngredientIds = selectedIngredientIds.toggle(id)
                                        }.padding(horizontal = 12.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Checkbox(
                                            checked = id in selectedIngredientIds,
                                            onCheckedChange = {
                                                selectedIngredientIds = selectedIngredientIds.toggle(id)
                                            },
                                        )
                                        Text(name)
                                    }
                                }
                                if (ingredients.isEmpty()) {
                                    Text("Add ingredients in Kitchen first.", modifier = Modifier.padding(12.dp))
                                }
                            }
                        }
                    }
                    OutlinedButton(onClick = onOpenShoppingList) { Text("Shopping list") }
                }
                filterError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }

            when {
                loading -> AppContentStatePanel(
                    modifier = Modifier.weight(1f),
                    state = AppContentState(
                        AppContentStateKind.Loading,
                        "Finding recipes",
                        "Checking your local recipes, inventory, and kitchen rules.",
                    ),
                )
                error != null -> AppContentStatePanel(
                    modifier = Modifier.weight(1f),
                    state = AppContentState(
                        AppContentStateKind.Error,
                        "Recommendations unavailable",
                        requireNotNull(error),
                        actionLabel = "Retry",
                    ),
                    onAction = { retryKey++ },
                )
                !hasRecipes -> AppContentStatePanel(
                    modifier = Modifier.weight(1f),
                    state = AppContentState(
                        AppContentStateKind.Empty,
                        "No saved recipes",
                        "Import recipes to see what you can make.",
                    ),
                )
                matches.isEmpty() -> AppContentStatePanel(
                    modifier = Modifier.weight(1f),
                    state = AppContentState(
                        AppContentStateKind.Empty,
                        "No recipes match these filters",
                        "Try changing the time, servings, category, or required ingredients.",
                    ),
                )
                else -> {
                    val included = matches.filter { it.status == CandidateStatus.INCLUDED }
                    val excluded = matches.filter { it.status == CandidateStatus.EXCLUDED }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (included.size == 1) "1 recipe eligible" else "${included.size} recipes eligible",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (excluded.isNotEmpty()) {
                            Text("${excluded.size} filtered out", style = MaterialTheme.typography.bodySmall)
                        }
                        Button(
                            enabled = !creatingList && selectedRecipeIds.isNotEmpty(),
                            onClick = {
                                val selected = included.filter { it.recipe.id in selectedRecipeIds }
                                scope.launch {
                                    creatingList = true
                                    actionError = null
                                    val existingList = withContext(Dispatchers.IO) {
                                        runSafely { workflows.getShoppingList.get() }
                                    }
                                    creatingList = false
                                    when {
                                        existingList.isFailure -> {
                                            actionError = "The saved shopping list could not be checked."
                                        }
                                        existingList.getOrNull() != null -> {
                                            pendingShoppingRecipes = selected
                                            showReplaceConfirmation = true
                                        }
                                        else -> saveSelectedRecipesAsShoppingList(selected)
                                    }
                                }
                            },
                        ) { Text(if (creatingList) "Creating…" else "Create shopping list") }
                    }
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        if (included.isEmpty()) {
                            item {
                                Text(
                                    "No recipes match these filters. The recipes below were filtered out for the reasons shown.",
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            item { SectionHeader("Recipes that match") }
                            items(included, key = { it.recipe.id.toString() }) { match ->
                                RecipeRecommendationCard(
                                    match = match,
                                    ingredientNames = ingredients.toMap(),
                                    selected = match.recipe.id in selectedRecipeIds,
                                    onSelectedChange = { selected ->
                                        selectedRecipeIds = if (selected) {
                                            selectedRecipeIds + match.recipe.id
                                        } else {
                                            selectedRecipeIds - match.recipe.id
                                        }
                                    },
                                )
                            }
                        }
                        if (excluded.isNotEmpty()) {
                            item { SectionHeader("Filtered out") }
                            items(excluded, key = { it.recipe.id.toString() }) { match ->
                                RecipeRecommendationCard(
                                    match = match,
                                    ingredientNames = ingredients.toMap(),
                                    selected = false,
                                    onSelectedChange = {},
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showReplaceConfirmation) {
        AlertDialog(
            onDismissRequest = {
                showReplaceConfirmation = false
                pendingShoppingRecipes = null
            },
            title = { Text("Replace saved shopping list?") },
            text = { Text("The current items and checked state will be replaced with a list from the selected recipes.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val selected = pendingShoppingRecipes
                        pendingShoppingRecipes = null
                        showReplaceConfirmation = false
                        if (selected != null) scope.launch { saveSelectedRecipesAsShoppingList(selected) }
                    },
                ) { Text("Replace list") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showReplaceConfirmation = false
                        pendingShoppingRecipes = null
                    },
                ) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun RecipeRecommendationCard(
    match: RecipeMatch,
    ingredientNames: Map<IngredientId, String>,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (match.status == CandidateStatus.INCLUDED) {
                    Checkbox(checked = selected, onCheckedChange = onSelectedChange)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(match.recipe.name, style = MaterialTheme.typography.titleMedium)
                    Text(match.readiness.label(), style = MaterialTheme.typography.labelLarge)
                }
            }
            Text(match.score.explanation(), style = MaterialTheme.typography.bodySmall)
            if (match.exclusions.isNotEmpty()) {
                Text("Why filtered out", style = MaterialTheme.typography.labelLarge)
                match.exclusions.forEach { exclusion ->
                    Text("• ${exclusion.label()}", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (match.ingredients.isNotEmpty()) {
                Text("Ingredient details", style = MaterialTheme.typography.labelLarge)
                match.ingredients.forEach { explanation ->
                    val name = ingredientNames[explanation.requirement.ingredientId] ?: explanation.requirement.rawLine
                    val quantity = explanation.quantity
                    val freshness = explanation.freshness
                    Text(
                        "• $name — ${quantity.status.label()}: ${quantity.reason.message}" +
                            if (freshness.isEmpty()) "" else {
                                "; freshness: " + freshness.joinToString {
                                    "${it.status.label()}${it.effectiveUseBy?.let { date -> " through $date" }.orEmpty()}"
                                }
                            },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            match.equipment.reasons.forEach { reason ->
                if (reason.status != dev.kitchenapp.core.domain.household.EquipmentStatus.IRRELEVANT) {
                    Text(
                        "• Equipment: ${reason.requested} — ${reason.status.label()}" +
                            (reason.satisfiedBy?.let { " (using $it)" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ShoppingListScreen(workflows: RecipeLibraryUseCases) {
    var shoppingList by remember { mutableStateOf<ShoppingList?>(null) }
    var recipeNames by remember { mutableStateOf<Map<RecipeId, String>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(retryKey) {
        loading = true
        val result = withContext(Dispatchers.IO) {
            runSafely {
                workflows.getShoppingList.get() to workflows.searchRecipes.search("")
            }
        }
        result.onSuccess { (list, recipes) ->
            shoppingList = list
            recipeNames = recipes.associate { it.id to it.name }
            error = null
        }.onFailure {
            error = "The saved shopping list could not be loaded."
        }
        loading = false
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Shopping list") }) },
    ) { innerPadding ->
        when {
            loading -> AppContentStatePanel(
                modifier = Modifier.padding(innerPadding),
                state = AppContentState(
                    AppContentStateKind.Loading,
                    "Loading shopping list",
                    "Opening your saved list on this device.",
                ),
            )
            error != null -> AppContentStatePanel(
                modifier = Modifier.padding(innerPadding),
                state = AppContentState(
                    AppContentStateKind.Error,
                    "Shopping list unavailable",
                    requireNotNull(error),
                    actionLabel = "Retry",
                ),
                onAction = { retryKey++ },
            )
            shoppingList == null -> AppContentStatePanel(
                modifier = Modifier.padding(innerPadding),
                state = AppContentState(
                    AppContentStateKind.Empty,
                    "No shopping list yet",
                    "Select eligible recipes and create a list from Recommendations.",
                ),
            )
            else -> ShoppingListContent(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                shoppingList = requireNotNull(shoppingList),
                recipeNames = recipeNames,
                actionError = actionError,
                onCheckChanged = { item, checked ->
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runSafely {
                                workflows.setShoppingListItemChecked.update(item.id, checked)
                            }
                        }
                        result.onSuccess { updated ->
                            if (updated) {
                                shoppingList = shoppingList?.let { current ->
                                    current.copy(items = current.items.map {
                                        if (it.id == item.id) it.copy(checked = checked) else it
                                    })
                                }
                                actionError = null
                            } else {
                                actionError = "That shopping item is no longer saved."
                            }
                        }.onFailure { actionError = "The item could not be updated." }
                    }
                },
                onClear = { confirmClear = true },
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear shopping list?") },
            text = { Text("This removes the saved list from this device.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runSafely { workflows.clearShoppingList.clear() }
                            }
                            result.onSuccess {
                                shoppingList = null
                                actionError = null
                                confirmClear = false
                            }.onFailure { actionError = "The list could not be cleared." }
                        }
                    },
                ) { Text("Clear list") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ShoppingListContent(
    modifier: Modifier,
    shoppingList: ShoppingList,
    recipeNames: Map<RecipeId, String>,
    actionError: String?,
    onCheckChanged: (ShoppingListItem, Boolean) -> Unit,
    onClear: () -> Unit,
) {
    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${shoppingList.items.count { it.checked }} of ${shoppingList.items.size} checked", modifier = Modifier.weight(1f))
            TextButton(onClick = onClear) { Text("Clear") }
        }
        actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (shoppingList.items.isEmpty()) {
            AppContentStatePanel(
                modifier = Modifier.weight(1f),
                state = AppContentState(AppContentStateKind.Empty, "No missing ingredients", "The selected recipes are covered by current inventory."),
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(shoppingList.items, key = { it.id.toString() }) { item ->
                    ShoppingListItemCard(item, recipeNames, onCheckChanged)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ShoppingListItemCard(
    item: ShoppingListItem,
    recipeNames: Map<RecipeId, String>,
    onCheckChanged: (ShoppingListItem, Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = item.checked,
            onCheckedChange = { checked -> onCheckChanged(item, checked) },
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(item.ingredientName, style = MaterialTheme.typography.titleMedium)
            val amount = item.quantity?.let { quantity ->
                "${quantity.display()}${item.unit?.let { " ${it.code}" }.orEmpty()}"
            } ?: "Amount unknown${item.unit?.let { " ${it.code}" }.orEmpty()}"
            Text(amount)
            Text(
                "For: " + item.sourceRecipeIds.joinToString { recipeNames[it] ?: "Saved recipe" },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun Rational.display(): String =
    if (denominator == 1L) numerator.toString() else "$numerator/$denominator"

private fun parseServings(value: String): Int? {
    val match = Regex("""^\s*(?:serves?\s*)?(\d+)(?:\s+servings?)?\s*$""", RegexOption.IGNORE_CASE)
        .matchEntire(value)
    return match?.groupValues?.getOrNull(1)?.toIntOrNull()
}

private fun Set<IngredientId>.toggle(id: IngredientId): Set<IngredientId> =
    if (id in this) this - id else this + id

private fun Set<String>.toggleValue(value: String): Set<String> =
    if (value in this) this - value else this + value

private fun ReadinessStatus.label(): String = when (this) {
    ReadinessStatus.READY -> "Ready now"
    ReadinessStatus.NEEDS_CONFIRMATION -> "Needs confirmation"
    ReadinessStatus.NOT_READY -> "Missing required ingredients"
}

private fun QuantityStatus.label(): String = when (this) {
    QuantityStatus.AVAILABLE -> "Available"
    QuantityStatus.INSUFFICIENT -> "Insufficient"
    QuantityStatus.MISSING -> "Missing"
    QuantityStatus.UNCERTAIN -> "Uncertain"
    QuantityStatus.NOT_REQUIRED -> "Optional"
}

private fun FreshnessStatus.label(): String = when (this) {
    FreshnessStatus.UNDATED -> "Undated"
    FreshnessStatus.FRESH -> "Fresh"
    FreshnessStatus.USE_SOON -> "Use soon"
    FreshnessStatus.PAST_EFFECTIVE_DATE -> "Past advisory date"
}

private fun MatchExclusion.label(): String = when (this) {
    MatchExclusion.HARD_INGREDIENT_EXCLUSION -> "Household rule blocks an ingredient"
    MatchExclusion.EQUIPMENT_UNAVAILABLE -> "Required equipment is unavailable"
    MatchExclusion.MEAL_TYPE_MISMATCH -> "Meal type does not match"
    MatchExclusion.TIME_LIMIT -> "Takes longer than the available time"
    MatchExclusion.TIME_UNKNOWN -> "Time estimate is unknown"
    MatchExclusion.SERVINGS_MISMATCH -> "Does not make enough servings"
    MatchExclusion.SERVINGS_UNKNOWN -> "Serving count is unknown"
    MatchExclusion.CATEGORY_MISMATCH -> "Does not match the selected category"
    MatchExclusion.REQUIRED_INGREDIENT_MISSING -> "Does not include every required ingredient"
}

private fun dev.kitchenapp.core.engine.matching.ScoreBreakdown.explanation(): String =
    "Score ${total}: readiness +${readiness}, coverage +${ingredientCoverage}, " +
        "use-soon +${useSoonBonus}, preferences −${preferencePenalty}, " +
        "equipment warnings −${equipmentWarningPenalty}"

private fun dev.kitchenapp.core.domain.household.EquipmentStatus.label(): String = when (this) {
    dev.kitchenapp.core.domain.household.EquipmentStatus.SATISFIED -> "available"
    dev.kitchenapp.core.domain.household.EquipmentStatus.WARNING -> "preferred equipment missing"
    dev.kitchenapp.core.domain.household.EquipmentStatus.BLOCKED -> "required equipment missing"
    dev.kitchenapp.core.domain.household.EquipmentStatus.IRRELEVANT -> "not relevant"
}

private suspend fun <T> runSafely(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (failure: Exception) {
    Result.failure(failure)
}
