package dev.kitchenapp.sharedui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import dev.kitchenapp.core.application.RecipeImportCandidate
import dev.kitchenapp.core.application.RecipeImportOutcome
import dev.kitchenapp.core.application.RecipeImportImageSource
import dev.kitchenapp.core.application.RecipeImportRequest
import dev.kitchenapp.core.application.RecipeLibraryFilter
import dev.kitchenapp.core.application.RecipeLibraryUseCases
import dev.kitchenapp.core.domain.ingredient.IngredientResolution
import dev.kitchenapp.core.domain.household.EquipmentRequirement
import dev.kitchenapp.core.domain.household.EquipmentRequirementKind
import dev.kitchenapp.core.domain.identifiers.RecipeId
import dev.kitchenapp.core.domain.recipe.CanonicalRecipeDraft
import dev.kitchenapp.core.domain.recipe.Recipe
import dev.kitchenapp.core.domain.recipe.RecipeIngredient
import dev.kitchenapp.core.domain.recipe.RecipeImage
import dev.kitchenapp.core.domain.recipe.formatRecipeServings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

@Composable
internal fun RecipeLibraryScreen(
    workflows: RecipeLibraryUseCases,
    refreshKey: Int,
    onOpenRecipe: (Recipe) -> Unit,
    onImport: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var recipes by remember { mutableStateOf<List<Recipe>>(emptyList()) }
    var categories by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedFilter by remember { mutableStateOf<RecipeLibraryFilter>(RecipeLibraryFilter.All) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableIntStateOf(0) }
    var filterMenuExpanded by remember { mutableStateOf(false) }
    var showCategoryManager by rememberSaveable { mutableStateOf(false) }
    var newCategory by rememberSaveable { mutableStateOf("") }
    var renameCategory by remember { mutableStateOf<String?>(null) }
    var renameCategoryValue by rememberSaveable { mutableStateOf("") }
    var categoryError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query, selectedFilter, refreshKey, retryKey) {
        loading = true
        val result = withContext(Dispatchers.IO) {
            runSafely {
                categories = workflows.listRecipeCategories.getAll()
                workflows.searchRecipes.search(query, selectedFilter)
            }
        }
        result.onSuccess {
            recipes = it
            error = null
            if (selectedFilter is RecipeLibraryFilter.Category &&
                categories.none { category -> category.equals((selectedFilter as RecipeLibraryFilter.Category).name, true) }
            ) {
                selectedFilter = RecipeLibraryFilter.All
            }
        }.onFailure {
            error = "Saved recipes could not be loaded."
        }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recipes") },
                actions = { Button(onClick = onImport) { Text("Import") } },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding(),
        ) {
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                value = query,
                onValueChange = { query = it },
                label = { Text("Search saved recipes") },
                singleLine = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    TextButton(onClick = { filterMenuExpanded = true }) {
                        Text(selectedFilter.label())
                    }
                    DropdownMenu(
                        expanded = filterMenuExpanded,
                        onDismissRequest = { filterMenuExpanded = false },
                    ) {
                        listOf(
                            RecipeLibraryFilter.All,
                            RecipeLibraryFilter.MostRecent,
                            RecipeLibraryFilter.Uncategorized,
                        ).forEach { filter ->
                            DropdownMenuItem(
                                text = { Text(filter.label()) },
                                onClick = {
                                    selectedFilter = filter
                                    filterMenuExpanded = false
                                },
                            )
                        }
                        categories.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category) },
                                onClick = {
                                    selectedFilter = RecipeLibraryFilter.Category(category)
                                    filterMenuExpanded = false
                                },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Manage categories") },
                            onClick = {
                                filterMenuExpanded = false
                                showCategoryManager = true
                            },
                        )
                    }
                }
            }
            when {
                loading -> AppContentStatePanel(
                    modifier = Modifier.weight(1f),
                    state = AppContentState(
                        AppContentStateKind.Loading,
                        "Loading recipes",
                        "Searching your local recipe library.",
                    ),
                )
                error != null -> AppContentStatePanel(
                    modifier = Modifier.weight(1f),
                    state = AppContentState(
                        AppContentStateKind.Error,
                        "Recipes unavailable",
                        requireNotNull(error),
                        actionLabel = "Retry",
                    ),
                    onAction = { retryKey++ },
                )
                recipes.isEmpty() -> AppContentStatePanel(
                    modifier = Modifier.weight(1f),
                    state = AppContentState(
                        AppContentStateKind.Empty,
                        if (query.isBlank()) "No recipes yet" else "No matching recipes",
                        if (query.isBlank()) "Import or create a recipe to start your library."
                        else "Try another search term.",
                        actionLabel = if (query.isBlank()) "Import a recipe" else null,
                    ),
                    onAction = onImport,
                )
                else -> LazyColumn(modifier = Modifier.weight(1f)) {
                    items(recipes, key = { it.id.toString() }) { recipe ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenRecipe(recipe) }
                                .padding(horizontal = 20.dp, vertical = 16.dp),
                        ) {
                            Text(recipe.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                recipe.ingredients.size.toString() + " ingredients",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (recipe.categories.isNotEmpty()) {
                                Text(
                                    recipe.categories.joinToString(" · "),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (showCategoryManager) {
        AlertDialog(
            onDismissRequest = { showCategoryManager = false; categoryError = null },
            title = { Text("Recipe categories") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = newCategory,
                        onValueChange = { newCategory = it; categoryError = null },
                        label = { Text("New category") },
                        singleLine = true,
                    )
                    Button(
                        enabled = newCategory.isNotBlank(),
                        onClick = {
                            runCatching { workflows.createRecipeCategory.create(newCategory) }
                                .onSuccess {
                                    newCategory = ""
                                    categoryError = null
                                    retryKey++
                                }
                                .onFailure { categoryError = it.message ?: "Category could not be created." }
                        },
                    ) { Text("Add category") }
                    categories.forEach { category ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(category, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                renameCategory = category
                                renameCategoryValue = category
                            }) { Text("Rename") }
                            TextButton(onClick = {
                                runCatching { workflows.removeRecipeCategory.remove(category) }
                                    .onSuccess {
                                        if (selectedFilter is RecipeLibraryFilter.Category &&
                                            (selectedFilter as RecipeLibraryFilter.Category).name.equals(category, ignoreCase = true)
                                        ) {
                                            selectedFilter = RecipeLibraryFilter.All
                                        }
                                        retryKey++
                                    }
                                    .onFailure { categoryError = it.message ?: "Category could not be removed." }
                            }) { Text("Remove") }
                        }
                    }
                    categoryError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCategoryManager = false; categoryError = null }) { Text("Done") }
            },
        )
    }

    renameCategory?.let { oldName ->
        AlertDialog(
            onDismissRequest = { renameCategory = null; categoryError = null },
            title = { Text("Rename category") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = renameCategoryValue,
                        onValueChange = { renameCategoryValue = it; categoryError = null },
                        label = { Text("Category name") },
                        singleLine = true,
                    )
                    categoryError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = renameCategoryValue.isNotBlank(),
                    onClick = {
                        runCatching { workflows.renameRecipeCategory.rename(oldName, renameCategoryValue) }
                            .onSuccess {
                                if (selectedFilter is RecipeLibraryFilter.Category &&
                                    (selectedFilter as RecipeLibraryFilter.Category).name.equals(oldName, ignoreCase = true)
                                ) {
                                    selectedFilter = RecipeLibraryFilter.Category(renameCategoryValue.trim())
                                }
                                renameCategory = null
                                categoryError = null
                                retryKey++
                            }
                            .onFailure { categoryError = it.message ?: "Category could not be renamed." }
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameCategory = null; categoryError = null }) { Text("Cancel") }
            },
        )
    }
}

private fun RecipeLibraryFilter.label(): String = when (this) {
    RecipeLibraryFilter.All -> "All recipes"
    RecipeLibraryFilter.MostRecent -> "Most Recent"
    RecipeLibraryFilter.Uncategorized -> "Uncategorized"
    is RecipeLibraryFilter.Category -> name
}

@Composable
internal fun RecipeDetailScreen(
    workflows: RecipeLibraryUseCases,
    recipeId: String,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onDeleted: () -> Unit,
) {
    var recipe by remember(recipeId) { mutableStateOf<Recipe?>(null) }
    var recipeImage by remember(recipeId) { mutableStateOf<RecipeImage?>(null) }
    var loadError by remember(recipeId) { mutableStateOf<String?>(null) }
    var loading by remember(recipeId) { mutableStateOf(true) }
    var editing by rememberSaveable(recipeId) { mutableStateOf(false) }
    var name by rememberSaveable(recipeId) { mutableStateOf("") }
    var servings by rememberSaveable(recipeId) { mutableStateOf("") }
    var directions by rememberSaveable(recipeId) { mutableStateOf("") }
    var notes by rememberSaveable(recipeId) { mutableStateOf("") }
    var categories by rememberSaveable(recipeId) { mutableStateOf("") }
    var mealTypes by rememberSaveable(recipeId) { mutableStateOf("") }
    var requiredEquipment by rememberSaveable(recipeId) { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var showDeleteConfirmation by rememberSaveable(recipeId) { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var imageBitmap by remember(recipeImage) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(recipeImage) {
        imageBitmap = recipeImage?.let { image ->
            withContext(Dispatchers.Default) { decodeRecipeImage(image.bytes) }
        }
    }

    LaunchedEffect(recipeId) {
        val id = RecipeId(Uuid.parse(recipeId))
        val result = withContext(Dispatchers.IO) {
            runSafely {
                val loaded = workflows.getRecipe.get(id)
                loaded to loaded?.let { workflows.getRecipeImage.get(id) }
            }
        }
        recipe = result.getOrNull()?.first
        recipeImage = result.getOrNull()?.second
        loadError = when {
            result.isFailure -> "Recipe could not be loaded."
            result.getOrNull()?.first == null -> "This recipe is no longer available."
            else -> null
        }
        loading = false
    }

    val loadedRecipe = recipe
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(loadedRecipe?.name ?: "Recipe") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    if (loadedRecipe != null && !editing) {
                        TextButton(
                            onClick = {
                                name = loadedRecipe.name
                                servings = loadedRecipe.servings.orEmpty()
                                directions = loadedRecipe.directionsText
                                notes = loadedRecipe.notes.orEmpty()
                                categories = loadedRecipe.categories.joinToString(", ")
                                mealTypes = loadedRecipe.mealTypes.joinToString(", ")
                                requiredEquipment = loadedRecipe.equipmentRequirements
                                    .filter { it.kind == EquipmentRequirementKind.REQUIRED }
                                    .joinToString(", ") { it.equipment }
                                editing = true
                            },
                        ) { Text("Edit") }
                        TextButton(
                            onClick = { showDeleteConfirmation = true; error = null },
                        ) { Text("Delete") }
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            loading -> AppContentStatePanel(
                modifier = Modifier.padding(innerPadding),
                state = AppContentState(AppContentStateKind.Loading, "Loading recipe", "Reading local recipe data."),
            )
            loadError != null -> AppContentStatePanel(
                modifier = Modifier.padding(innerPadding),
                state = AppContentState(AppContentStateKind.Error, "Recipe unavailable", requireNotNull(loadError)),
            )
            loadedRecipe == null -> Unit
            editing -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(name, { name = it }, label = { Text("Recipe name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(servings, { servings = it }, label = { Text("Servings") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    directions,
                    { directions = it },
                    label = { Text("Directions") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                )
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                OutlinedTextField(
                    categories,
                    { categories = it },
                    label = { Text("Categories, comma separated") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    mealTypes,
                    { mealTypes = it },
                    label = { Text("Meal types, comma separated") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    requiredEquipment,
                    { requiredEquipment = it },
                    label = { Text("Required equipment, comma separated") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { editing = false; error = null }) { Text("Cancel") }
                    Button(
                        enabled = !saving && name.isNotBlank(),
                        onClick = {
                            scope.launch {
                                saving = true
                                try {
                                    val updated = loadedRecipe.copy(
                                        name = name.trim(),
                                        servings = servings.trim().ifBlank { null },
                                        directionsText = directions.trim(),
                                        notes = notes.trim().ifBlank { null },
                                        categories = categories.split(',').map(String::trim)
                                            .filter(String::isNotBlank).distinctBy(String::lowercase),
                                        mealTypes = mealTypes.splitNames(),
                                        equipmentRequirements = loadedRecipe.equipmentRequirements
                                            .filter { it.kind != EquipmentRequirementKind.REQUIRED } +
                                            requiredEquipment.toEquipmentRequirements(),
                                    )
                                    val result = withContext(Dispatchers.IO) {
                                        runSafely { workflows.saveReviewedRecipe.save(updated) }
                                    }
                                    result.onSuccess {
                                        recipe = it
                                        editing = false
                                        error = null
                                        onSaved()
                                    }.onFailure {
                                        error = "Recipe could not be saved. Review its ingredients and try again."
                                    }
                                } finally {
                                    saving = false
                                }
                            }
                        },
                    ) { Text(if (saving) "Saving…" else "Save") }
                }
            }
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    imageBitmap?.let { bitmap ->
                        val imageLink = loadedRecipe.imageSourceUrl ?: loadedRecipe.sourceUrl
                        val imageModifier = Modifier.fillMaxWidth().height(220.dp)
                        Image(
                            bitmap = bitmap,
                            contentDescription = loadedRecipe.name + " source image",
                            contentScale = ContentScale.Crop,
                            modifier = if (imageLink == null) imageModifier else imageModifier.clickable {
                                runCatching { uriHandler.openUri(imageLink) }
                            },
                        )
                    }
                    Text(formatRecipeServings(loadedRecipe.servings) ?: "Servings not specified")
                    loadedRecipe.prepTimeSeconds?.let { Text("Prep: " + it.formatRecipeTime()) }
                    loadedRecipe.cookTimeSeconds?.let { Text("Cook: " + it.formatRecipeTime()) }
                    loadedRecipe.chillingTimeSeconds?.let { Text("Chilling: " + it.formatRecipeTime()) }
                    loadedRecipe.totalTimeSeconds?.let { Text("Total: " + it.formatRecipeTime()) }
                    loadedRecipe.sourceUrl?.let { source ->
                        TextButton(onClick = { runCatching { uriHandler.openUri(source) } }) {
                            Text("View original recipe")
                        }
                    }
                    loadedRecipe.imageSourceUrl?.takeIf { it != loadedRecipe.sourceUrl }?.let { imageSource ->
                        TextButton(onClick = { runCatching { uriHandler.openUri(imageSource) } }) {
                            Text("View image source")
                        }
                    }
                    if (loadedRecipe.categories.isNotEmpty()) {
                        Text("Categories: " + loadedRecipe.categories.joinToString(" · "))
                    }
                    if (loadedRecipe.mealTypes.isNotEmpty()) {
                        Text("Meal types: " + loadedRecipe.mealTypes.joinToString(" · "))
                    }
                    if (loadedRecipe.equipmentRequirements.isNotEmpty()) {
                        Text(
                            "Equipment: " + loadedRecipe.equipmentRequirements.joinToString(" · ") {
                                "${it.equipment} (${it.kind.name.lowercase()})"
                            },
                        )
                    }
                    Text("Ingredients", style = MaterialTheme.typography.titleMedium)
                }
                items(loadedRecipe.ingredients, key = { it.id.toString() }) { ingredient ->
                    Text(ingredient.rawLine)
                }
                item {
                    Text("Directions", style = MaterialTheme.typography.titleMedium)
                    Text(loadedRecipe.directionsText.toDisplayText().ifBlank { "No directions provided." })
                    loadedRecipe.notes?.takeIf(String::isNotBlank)?.let {
                        Text("Notes", style = MaterialTheme.typography.titleMedium)
                        Text(it.toDisplayText())
                    }
                }
            }
        }
    }
    if (showDeleteConfirmation && loadedRecipe != null) {
        AlertDialog(
            onDismissRequest = { if (!deleting) showDeleteConfirmation = false },
            title = { Text("Delete recipe?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This permanently removes " + loadedRecipe.name + " from your recipe library.")
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        scope.launch {
                            deleting = true
                            error = null
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    runSafely { workflows.deleteRecipe.delete(loadedRecipe.id) }
                                }
                                result.onSuccess { deleted ->
                                    if (deleted) {
                                        showDeleteConfirmation = false
                                        onDeleted()
                                    } else {
                                        error = "Recipe is no longer available."
                                    }
                                }.onFailure {
                                    error = it.message ?: "Recipe could not be deleted."
                                }
                            } finally {
                                deleting = false
                            }
                        }
                    },
                ) { Text(if (deleting) "Deleting…" else "Delete") }
            },
            dismissButton = {
                TextButton(enabled = !deleting, onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

private enum class RecipeImportMode {
    Url,
    Manual,
    Paprika,
}

@Composable
internal fun RecipeImportScreen(
    workflows: RecipeLibraryUseCases,
    candidates: List<RecipeImportCandidate>,
    completedSourceKeys: Set<String>,
    onCandidatesChanged: (List<RecipeImportCandidate>) -> Unit,
    onReviewCandidate: (Int) -> Unit,
    onRequestPaprikaArchive: ((String?) -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    var modeName by rememberSaveable { mutableStateOf(RecipeImportMode.Url.name) }
    val mode = RecipeImportMode.valueOf(modeName)
    var url by rememberSaveable { mutableStateOf("") }
    var manualName by rememberSaveable { mutableStateOf("") }
    var manualServings by rememberSaveable { mutableStateOf("") }
    var manualIngredients by rememberSaveable { mutableStateOf("") }
    var manualDirections by rememberSaveable { mutableStateOf("") }
    var manualNotes by rememberSaveable { mutableStateOf("") }
    var manualCategories by rememberSaveable { mutableStateOf("") }
    var manualSourceUrl by rememberSaveable { mutableStateOf("") }
    var loading by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun preview(request: RecipeImportRequest) {
        if (loading) return
        scope.launch {
            loading = true
            error = null
            onCandidatesChanged(emptyList())
            try {
                val result = withContext(Dispatchers.IO) {
                    runSafely { workflows.previewRecipeImport.preview(request) }
                }
                result.onSuccess(onCandidatesChanged).onFailure {
                    error = "Import preview failed. Check the input and try again."
                }
            } finally {
                loading = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import recipes") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RecipeImportMode.entries.forEach { option ->
                        if (option == mode) {
                            Button(onClick = { modeName = option.name }) { Text(option.label()) }
                        } else {
                            OutlinedButton(onClick = { modeName = option.name }) { Text(option.label()) }
                        }
                    }
                }
            }
            item {
                when (mode) {
                    RecipeImportMode.Url -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Recipe URL") },
                            singleLine = true,
                        )
                        Button(enabled = !loading && url.isNotBlank(), onClick = { preview(RecipeImportRequest.Url(url.trim())) }) {
                            Text("Preview URL")
                        }
                    }

                    RecipeImportMode.Manual -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(manualName, { manualName = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Recipe name") }, singleLine = true)
                        OutlinedTextField(manualServings, { manualServings = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Servings") }, singleLine = true)
                        OutlinedTextField(
                            manualIngredients,
                            { manualIngredients = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Ingredients, one per line") },
                            minLines = 3,
                        )
                        OutlinedTextField(
                            manualDirections,
                            { manualDirections = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Directions") },
                            minLines = 3,
                        )
                        OutlinedTextField(manualNotes, { manualNotes = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Notes") }, minLines = 2)
                        OutlinedTextField(manualCategories, { manualCategories = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Categories, comma separated") }, singleLine = true)
                        OutlinedTextField(manualSourceUrl, { manualSourceUrl = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Source URL, optional") }, singleLine = true)
                        Button(
                            enabled = !loading && manualName.isNotBlank(),
                            onClick = {
                                preview(
                                    RecipeImportRequest.Manual(
                                        name = manualName,
                                        servings = manualServings.ifBlank { null },
                                        ingredientLines = manualIngredients.lines(),
                                        directionsText = manualDirections,
                                        notes = manualNotes.ifBlank { null },
                                        categories = manualCategories.split(',').map(String::trim).filter(String::isNotBlank),
                                        sourceUrl = manualSourceUrl.ifBlank { null },
                                    ),
                                )
                            },
                        ) { Text("Preview manual recipe") }
                    }

                    RecipeImportMode.Paprika -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Choose a .paprikarecipes archive. Each archive entry will have its own outcome.")
                        Button(
                            enabled = !loading,
                            onClick = {
                                onRequestPaprikaArchive { source ->
                                    if (!source.isNullOrBlank()) preview(RecipeImportRequest.PaprikaArchive(source))
                                }
                            },
                        ) { Text("Choose archive") }
                    }
                }
            }

            if (loading) {
                item {
                    AppContentStatePanel(
                        modifier = Modifier.height(200.dp),
                        state = AppContentState(AppContentStateKind.Loading, "Preparing preview", "Reading import data."),
                    )
                }
            } else if (error != null) {
                item { Text(requireNotNull(error), color = MaterialTheme.colorScheme.error) }
            } else if (candidates.isNotEmpty()) {
                item { Text("Import results", style = MaterialTheme.typography.titleMedium) }
                items(candidates.indices.toList(), key = { candidates[it].sourceKey + ":" + it }) { index ->
                    ImportCandidateCard(
                        candidate = candidates[index],
                        completed = candidates[index].sourceKey in completedSourceKeys,
                        onReview = { onReviewCandidate(index) },
                        onManualFallback = {
                            val candidate = candidates[index]
                            modeName = RecipeImportMode.Manual.name
                            manualName = candidate.suggestedName.orEmpty()
                            manualServings = ""
                            manualIngredients = ""
                            manualDirections = ""
                            manualNotes = ""
                            manualCategories = ""
                            manualSourceUrl = candidate.sourceKey.takeIf { it.startsWith("http://") || it.startsWith("https://") }.orEmpty()
                        },
                    )
                }
            } else {
                item {
                    AppContentStatePanel(
                        modifier = Modifier.height(200.dp),
                        state = AppContentState(
                            AppContentStateKind.Empty,
                            "Ready to import",
                            "Choose a source to preview recipes before saving them.",
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportCandidateCard(
    candidate: RecipeImportCandidate,
    completed: Boolean,
    onReview: () -> Unit,
    onManualFallback: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(candidate.draft?.name ?: candidate.suggestedName ?: candidate.sourceKey, style = MaterialTheme.typography.titleMedium)
            Text(candidate.outcome.label(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            candidate.reason?.let { Text(it, color = if (candidate.outcome == RecipeImportOutcome.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            candidate.warnings.forEach { Text("• $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            candidate.duplicateOf?.let { Text("Possible duplicate of $it", color = MaterialTheme.colorScheme.error) }
            when {
                completed -> Text("Saved", color = MaterialTheme.colorScheme.primary)
                candidate.draft != null -> Button(onClick = onReview) { Text("Review recipe") }
                candidate.outcome == RecipeImportOutcome.NEEDS_REVIEW -> TextButton(onClick = onManualFallback) { Text("Enter manually") }
            }
        }
    }
}

@Composable
internal fun RecipeImportReviewScreen(
    candidate: RecipeImportCandidate?,
    workflows: RecipeLibraryUseCases,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val selectedCandidate = candidate
    val draft = selectedCandidate?.draft
    if (selectedCandidate == null || draft == null) {
        AppContentStatePanel(
            state = AppContentState(AppContentStateKind.Error, "Import preview unavailable", "Return to the import results and choose a recipe again."),
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    val namesSeparator = "\u001f"
    var name by rememberSaveable(selectedCandidate.sourceKey) { mutableStateOf(draft.name) }
    var servings by rememberSaveable(selectedCandidate.sourceKey) { mutableStateOf(draft.servings.orEmpty()) }
    var directions by rememberSaveable(selectedCandidate.sourceKey) { mutableStateOf(draft.directionsText) }
    var notes by rememberSaveable(selectedCandidate.sourceKey) { mutableStateOf(draft.notes.orEmpty()) }
    var categories by rememberSaveable(selectedCandidate.sourceKey) {
        mutableStateOf(draft.categories.joinToString(", "))
    }
    var mealTypes by rememberSaveable(selectedCandidate.sourceKey) { mutableStateOf("") }
    var requiredEquipment by rememberSaveable(selectedCandidate.sourceKey) { mutableStateOf("") }
    var duplicateConfirmed by rememberSaveable(selectedCandidate.sourceKey) { mutableStateOf(false) }
    var sourceImage by remember(selectedCandidate.sourceKey) { mutableStateOf<RecipeImage?>(null) }
    var sourceImageBitmap by remember(sourceImage) { mutableStateOf<ImageBitmap?>(null) }
    var imageLoading by remember(selectedCandidate.sourceKey) { mutableStateOf(false) }
    var imageWarning by remember(selectedCandidate.sourceKey) { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current
    val imageSourceUrl = when (val source = selectedCandidate.imageSource) {
        is RecipeImportImageSource.Url -> source.url
        is RecipeImportImageSource.PaprikaArchiveEntry -> draft.imageSourceUrl
        null -> draft.imageSourceUrl
    }

    LaunchedEffect(selectedCandidate.sourceKey, selectedCandidate.imageSource) {
        val source = selectedCandidate.imageSource
        if (source == null) return@LaunchedEffect
        imageLoading = true
        val result = withContext(Dispatchers.IO) {
            runSafely { workflows.loadRecipeImage.load(source) }
        }
        result.onSuccess {
            sourceImage = it
            if (it == null) imageWarning = "Source image could not be loaded; the recipe link is still available."
        }.onFailure {
            imageWarning = "Source image could not be loaded; the recipe link is still available."
        }
        imageLoading = false
    }

    LaunchedEffect(sourceImage) {
        sourceImageBitmap = sourceImage?.let { image ->
            withContext(Dispatchers.Default) { decodeRecipeImage(image.bytes) }
        }
    }
    var ingredientNamesEncoded by rememberSaveable(selectedCandidate.sourceKey) {
        mutableStateOf(
            draft.ingredients.joinToString(namesSeparator) { ingredient ->
                when (val resolution = ingredient.resolution) {
                    is IngredientResolution.Resolved -> resolution.ingredient.name
                    is IngredientResolution.NeedsReview -> ""
                    is IngredientResolution.Unresolved -> resolution.input
                }
            },
        )
    }
    val ingredientNames = remember(ingredientNamesEncoded, draft.ingredients.size) {
        ingredientNamesEncoded.split(namesSeparator).let { names ->
            List(draft.ingredients.size) { names.getOrElse(it) { "" } }
        }
    }
    fun setIngredientName(index: Int, value: String) {
        ingredientNamesEncoded = ingredientNames.mapIndexed { position, current ->
            if (position == index) value else current
        }.joinToString(namesSeparator)
    }

    var saving by remember { mutableStateOf(false) }
    var error by rememberSaveable(selectedCandidate.sourceKey) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review import") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(selectedCandidate.outcome.label(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                if (selectedCandidate.duplicateOf != null) {
                    Text("Possible duplicate of ${selectedCandidate.duplicateOf}", color = MaterialTheme.colorScheme.error)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = duplicateConfirmed, onCheckedChange = { duplicateConfirmed = it })
                        Text("Save another copy anyway")
                    }
                }
                if (imageLoading) Text("Loading source image…")
                sourceImageBitmap?.let { bitmap ->
                    val imageLink = imageSourceUrl ?: draft.sourceUrl
                    val imageModifier = Modifier.fillMaxWidth().height(220.dp)
                    Image(
                        bitmap = bitmap,
                        contentDescription = draft.name + " source image",
                        contentScale = ContentScale.Crop,
                        modifier = if (imageLink == null) imageModifier else imageModifier.clickable {
                            runCatching { uriHandler.openUri(imageLink) }
                        },
                    )
                }
                imageWarning?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                imageSourceUrl?.let { imageUrl ->
                    TextButton(onClick = { runCatching { uriHandler.openUri(imageUrl) } }) {
                        Text("View image source")
                    }
                }
                draft.sourceUrl?.let { sourceUrl ->
                    TextButton(onClick = { runCatching { uriHandler.openUri(sourceUrl) } }) {
                        Text("View original recipe")
                    }
                }
                OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Recipe name") })
                OutlinedTextField(servings, { servings = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Servings") }, singleLine = true)
                OutlinedTextField(directions, { directions = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Directions") }, minLines = 3)
                OutlinedTextField(notes, { notes = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Notes") }, minLines = 2)
                OutlinedTextField(
                    categories,
                    { categories = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Categories, comma separated") },
                    singleLine = true,
                )
                OutlinedTextField(
                    mealTypes,
                    { mealTypes = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Meal types, comma separated") },
                    singleLine = true,
                )
                OutlinedTextField(
                    requiredEquipment,
                    { requiredEquipment = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Required equipment, comma separated") },
                    singleLine = true,
                )
                Text("Review ingredients", style = MaterialTheme.typography.titleMedium)
            }
            items(draft.ingredients.indices.toList(), key = { "${selectedCandidate.sourceKey}:ingredient:$it" }) { index ->
                val ingredient = draft.ingredients[index]
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(ingredient.rawLine, style = MaterialTheme.typography.bodyMedium)
                    when (val resolution = ingredient.resolution) {
                        is IngredientResolution.Resolved -> Text("Matched: ${resolution.ingredient.name}")
                        is IngredientResolution.NeedsReview -> {
                            Text("Choose a canonical match")
                            resolution.candidates.forEach { option ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = ingredientNames[index] == option.name,
                                        onClick = { setIngredientName(index, option.name) },
                                    )
                                    Text(option.name)
                                }
                            }
                        }
                        is IngredientResolution.Unresolved -> Text("No existing canonical match")
                    }
                    OutlinedTextField(
                        value = ingredientNames[index],
                        onValueChange = { setIngredientName(index, it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Canonical ingredient name") },
                        singleLine = true,
                    )
                }
            }
            item {
                selectedCandidate.warnings.forEach { Text("• $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    enabled = !saving && name.isNotBlank() &&
                        ingredientNames.all(String::isNotBlank) &&
                        (selectedCandidate.duplicateOf == null || duplicateConfirmed),
                    onClick = {
                        scope.launch {
                            saving = true
                            error = null
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    runSafely {
                                        val canonicalIngredients = workflows.addCanonicalIngredient.addAll(ingredientNames)
                                        val recipe = draft.toRecipe(
                                            name = name.trim(),
                                            servings = servings.trim().ifBlank { null },
                                            directions = directions.trim(),
                                            notes = notes.trim().ifBlank { null },
                                            categories = categories.split(',').map(String::trim)
                                                .filter(String::isNotBlank).distinctBy(String::lowercase),
                                            imageSourceUrl = imageSourceUrl,
                                            mealTypes = mealTypes.splitNames(),
                                            equipmentRequirements = requiredEquipment.toEquipmentRequirements(),
                                            canonicalIngredients = canonicalIngredients,
                                        )
                                        workflows.saveReviewedRecipe.save(recipe, sourceImage)
                                    }
                                }
                                result.onSuccess { onSaved() }.onFailure {
                                    error = it.message ?: "Recipe could not be saved."
                                }
                            } finally {
                                saving = false
                            }
                        }
                    },
                ) { Text(if (saving) "Saving…" else "Save recipe") }
            }
        }
    }
}

private fun CanonicalRecipeDraft.toRecipe(
    name: String,
    servings: String?,
    directions: String,
    notes: String?,
    categories: List<String>,
    imageSourceUrl: String?,
    mealTypes: List<String>,
    equipmentRequirements: List<EquipmentRequirement>,
    canonicalIngredients: List<dev.kitchenapp.core.domain.ingredient.Ingredient>,
): Recipe = Recipe(
    name = name,
    servings = servings,
    ingredients = ingredients.mapIndexed { index, imported ->
        val ingredient = canonicalIngredients[index]
        RecipeIngredient(
            ingredientId = ingredient.id,
            quantity = imported.quantity,
            quantityHigh = imported.quantityHigh,
            unit = imported.unit,
            preparation = imported.preparation,
            importance = imported.importance,
            approximateLevel = imported.approximateLevel,
            rawLine = imported.rawLine,
        )
    },
    directionsText = directions,
    notes = notes,
    categories = categories,
    sourceName = sourceName,
    sourceUrl = sourceUrl,
    prepTimeSeconds = prepTimeSeconds,
    cookTimeSeconds = cookTimeSeconds,
    chillingTimeSeconds = chillingTimeSeconds,
    totalTimeSeconds = totalTimeSeconds,
    imageSourceUrl = imageSourceUrl,
    mealTypes = mealTypes,
    equipmentRequirements = equipmentRequirements,
)

private fun String.splitNames(): List<String> = split(',').map(String::trim)
    .filter(String::isNotBlank).distinctBy(String::lowercase)

private fun String.toEquipmentRequirements(): List<EquipmentRequirement> = splitNames().map { equipment ->
    EquipmentRequirement(equipment, EquipmentRequirementKind.REQUIRED)
}

private fun String.toDisplayText(): String = this
    .replace("&nbsp;", " ")
    .replace("&#160;", " ")
    .replace("&#39;", "'")
    .replace("&quot;", "\"")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&amp;", "&")

private fun Long.formatRecipeTime(): String {
    val totalMinutes = (this + 59) / 60
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours == 0L -> "$totalMinutes min"
        minutes == 0L -> "$hours hr"
        else -> "$hours hr $minutes min"
    }
}

private fun RecipeImportMode.label(): String = when (this) {
    RecipeImportMode.Url -> "URL"
    RecipeImportMode.Manual -> "Manual"
    RecipeImportMode.Paprika -> "Paprika"
}

private suspend fun <T> runSafely(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (failure: Exception) {
    Result.failure(failure)
}

private fun RecipeImportOutcome.label(): String = when (this) {
    RecipeImportOutcome.SUCCESS -> "Ready to save"
    RecipeImportOutcome.SUCCESS_WITH_WARNINGS -> "Review warnings"
    RecipeImportOutcome.NEEDS_REVIEW -> "Needs manual review"
    RecipeImportOutcome.FAILED -> "Import failed"
    RecipeImportOutcome.SKIPPED -> "Skipped"
}
