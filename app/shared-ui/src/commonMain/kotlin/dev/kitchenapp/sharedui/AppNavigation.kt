package dev.kitchenapp.sharedui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import dev.kitchenapp.core.application.ActiveCookingSession
import dev.kitchenapp.core.application.KitchenBackupRestoreDecision
import dev.kitchenapp.core.application.KitchenBackupRestorePreview
import dev.kitchenapp.core.application.KitchenBackupRestoreRequest
import dev.kitchenapp.core.application.KitchenBackupWorkflows
import dev.kitchenapp.core.application.RecipeImportCandidate
import dev.kitchenapp.core.application.RecipeLibraryUseCases
import dev.kitchenapp.core.domain.time.DomainInstant
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

@Serializable
internal data object RecipesRoute : NavKey

@Serializable
private data object RecommendationsRoute : NavKey

@Serializable
private data class RecipeDetailsRoute(val recipeId: String) : NavKey

@Serializable
private data object RecipeImportRoute : NavKey

@Serializable
private data class RecipeImportReviewRoute(val candidateIndex: Int) : NavKey

@Serializable
private data object KitchenRoute : NavKey

@Serializable
internal data object CookingRoute : NavKey

@Serializable
private data object ShoppingRoute : NavKey

@Serializable
private data object BackupRoute : NavKey

internal fun initialAppRoute(activeSession: ActiveCookingSession?): NavKey =
    if (activeSession == null) RecipesRoute else CookingRoute

private val appNavigationSavedStateConfiguration = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(serializer = RecipesRoute.serializer())
            subclass(serializer = RecommendationsRoute.serializer())
            subclass(serializer = RecipeDetailsRoute.serializer())
            subclass(serializer = RecipeImportRoute.serializer())
            subclass(serializer = RecipeImportReviewRoute.serializer())
            subclass(serializer = KitchenRoute.serializer())
            subclass(serializer = CookingRoute.serializer())
            subclass(serializer = ShoppingRoute.serializer())
            subclass(serializer = BackupRoute.serializer())
        }
    }
}

private data class AppDestination(
    val route: NavKey,
    val label: String,
    val symbol: String,
)

private val appDestinations = listOf(
    AppDestination(RecipesRoute, "Recipes", "R"),
    AppDestination(RecommendationsRoute, "Make", "M"),
    AppDestination(KitchenRoute, "Kitchen", "K"),
    AppDestination(CookingRoute, "Cook", "C"),
    AppDestination(ShoppingRoute, "Shopping", "S"),
    AppDestination(BackupRoute, "Backup", "B"),
)

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun KitchenAppNavigation(
    workflows: RecipeLibraryUseCases,
    onRequestPaprikaArchive: ((String?) -> Unit) -> Unit,
    cookingWorkflows: CookingWorkflows,
    cookingPlatform: CookingPlatform,
    backupWorkflows: KitchenBackupWorkflows?,
    backupDocumentResult: BackupDocumentResult?,
    onConsumeBackupDocumentResult: () -> Unit,
    onRequestBackupExport: (ByteArray) -> Unit,
    onRequestBackupImport: () -> Unit,
) {
    val coordinator = remember(cookingWorkflows, cookingPlatform) { CookingCoordinator(cookingWorkflows, cookingPlatform) }
    DisposableEffect(coordinator, cookingPlatform) {
        coordinator.refresh()
        cookingPlatform.bindLifecycle(coordinator::away, coordinator::returned)
        cookingPlatform.bindStatus(coordinator::onPlatformStatus)
        onDispose {
            cookingPlatform.unbindStatus()
            cookingPlatform.unbindLifecycle()
        }
    }
    val initialRoute = remember(cookingWorkflows) {
        initialAppRoute(cookingWorkflows.sessions.getActive())
    }
    val backStack = rememberNavBackStack(appNavigationSavedStateConfiguration, initialRoute)
    LaunchedEffect(Unit) {
        if (initialRoute == CookingRoute && backStack.lastOrNull() != CookingRoute) {
            backStack.clear()
            backStack.add(CookingRoute)
        }
    }
    var requestedEventId by remember { mutableStateOf<String?>(null) }
    DisposableEffect(cookingPlatform, coordinator) {
        cookingPlatform.bindReminderOpen { eventId ->
            coordinator.refresh()
            requestedEventId = eventId
            backStack.clear()
            backStack.add(CookingRoute)
        }
        onDispose { cookingPlatform.unbindReminderOpen() }
    }
    val currentRoute = backStack.lastOrNull()
    val selectedTopLevelRoute = when (currentRoute) {
        is RecipeDetailsRoute, RecipeImportRoute, is RecipeImportReviewRoute, RecipesRoute -> RecipesRoute
        RecommendationsRoute -> RecommendationsRoute
        KitchenRoute -> KitchenRoute
        CookingRoute -> CookingRoute
        ShoppingRoute -> ShoppingRoute
        BackupRoute -> BackupRoute
        else -> RecipesRoute
    }
    val importCandidates = remember { mutableStateListOf<RecipeImportCandidate>() }
    val completedSourceKeys = remember { mutableStateListOf<String>() }
    var recipeRefreshKey by remember { mutableIntStateOf(0) }
    var backupRestoreState by remember { mutableStateOf<BackupRestoreScreenState>(BackupRestoreScreenState.Empty) }
    var pendingRestore by remember { mutableStateOf<Pair<KitchenBackupRestoreRequest, KitchenBackupRestorePreview>?>(null) }
    val backupCoroutineScope = rememberCoroutineScope()
    val visibleDestinations = remember(backupWorkflows) {
        appDestinations.filter { it.route != BackupRoute || backupWorkflows != null }
    }
    val listDetailStrategy = rememberListDetailSceneStrategy<NavKey>()

    val exportBackup: () -> Unit = {
        if (backupWorkflows != null) {
            pendingRestore = null
            backupRestoreState = BackupRestoreScreenState.Loading("Preparing backup…")
            backupCoroutineScope.launch {
                runCatching {
                    withContext(Dispatchers.Default) {
                        backupWorkflows.export.export(DomainInstant(Clock.System.now()))
                    }
                }.fold(
                    onSuccess = { archive ->
                        backupRestoreState = BackupRestoreScreenState.Loading("Choose where to save the backup.")
                        onRequestBackupExport(archive)
                    },
                    onFailure = { error ->
                        backupRestoreState = BackupRestoreScreenState.Error(
                            error.message ?: "Could not prepare the backup.",
                        )
                    },
                )
            }
        }
    }
    val importBackup: () -> Unit = {
        if (backupWorkflows != null) {
            pendingRestore = null
            backupRestoreState = BackupRestoreScreenState.Loading("Choose a backup file.")
            onRequestBackupImport()
        }
    }
    val replaceAllBackupData: () -> Unit = {
        val current = pendingRestore
        if (backupWorkflows != null && current != null) {
            val (request, preview) = current
            backupRestoreState = BackupRestoreScreenState.Loading("Restoring backup.")
            backupCoroutineScope.launch {
                val result = withContext(Dispatchers.Default) {
                    runCatching {
                        backupWorkflows.applyRestore.apply(
                            request,
                            preview,
                            KitchenBackupRestoreDecision.REPLACE_ALL_LOCAL_DATA,
                        )
                    }
                }
                result.fold(
                    onSuccess = {
                        pendingRestore = null
                        importCandidates.clear()
                        completedSourceKeys.clear()
                        recipeRefreshKey++
                        coordinator.refresh()
                        backStack.clear()
                        backStack.add(BackupRoute)
                        backupRestoreState = BackupRestoreScreenState.Success(
                            "Backup restored. Recipes, kitchen data, cooking history, and shopping list are reloaded.",
                        )
                    },
                    onFailure = { error ->
                        backupCoroutineScope.launch {
                            val refreshed = withContext(Dispatchers.Default) {
                                runCatching { backupWorkflows.previewRestore.preview(request) }
                            }
                            refreshed.fold(
                                onSuccess = { newPreview ->
                                    pendingRestore = request to newPreview
                                    backupRestoreState = BackupRestoreScreenState.Review(
                                        preview = newPreview,
                                        message = "Restore was not applied. Review the updated preview and confirm again. " +
                                            (error.message ?: ""),
                                    )
                                },
                                onFailure = { refreshError ->
                                    pendingRestore = null
                                    backupRestoreState = BackupRestoreScreenState.Error(
                                        "Restore was not applied, and the preview could not be refreshed. " +
                                            (refreshError.message ?: error.message ?: "Try importing the backup again."),
                                    )
                                },
                            )
                        }
                    },
                )
            }
        }
    }
    val cancelBackupRestore: () -> Unit = {
        pendingRestore = null
        backupRestoreState = BackupRestoreScreenState.Empty
    }

    LaunchedEffect(backupDocumentResult) {
        val result = backupDocumentResult ?: return@LaunchedEffect
        when (result) {
            BackupDocumentResult.ExportSucceeded -> {
                backupRestoreState = BackupRestoreScreenState.Success("Backup saved.")
            }

            is BackupDocumentResult.ImportSelected -> {
                if (backupWorkflows == null) {
                    backupRestoreState = BackupRestoreScreenState.Error("Backup and restore are unavailable.")
                } else {
                    backupRestoreState = BackupRestoreScreenState.Loading("Validating backup and preparing preview.")
                    runCatching {
                        withContext(Dispatchers.Default) {
                            val request = backupWorkflows.decode.decode(result.bytes)
                            request to backupWorkflows.previewRestore.preview(request)
                        }
                    }.fold(
                        onSuccess = { (request, preview) ->
                            pendingRestore = request to preview
                            backupRestoreState = BackupRestoreScreenState.Review(preview)
                        },
                        onFailure = { error ->
                            pendingRestore = null
                            backupRestoreState = BackupRestoreScreenState.Error(
                                error.message ?: "Could not validate the selected backup.",
                            )
                        },
                    )
                }
            }

            BackupDocumentResult.PickerCancelled -> {
                pendingRestore = null
                backupRestoreState = BackupRestoreScreenState.Empty
            }

            is BackupDocumentResult.Failed -> {
                pendingRestore = null
                backupRestoreState = BackupRestoreScreenState.Error(result.message)
            }
        }
        onConsumeBackupDocumentResult()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val layoutType = if (maxWidth < 600.dp) {
            NavigationSuiteType.NavigationBar
        } else {
            NavigationSuiteType.NavigationRail
        }

        NavigationSuiteScaffold(
            layoutType = layoutType,
            navigationSuiteItems = {
                visibleDestinations.forEach { destination ->
                    item(
                        selected = destination.route == selectedTopLevelRoute,
                        onClick = {
                            backStack.clear()
                            backStack.add(destination.route)
                        },
                        icon = { Text(destination.symbol) },
                        label = { Text(destination.label) },
                    )
                }
            },
        ) {
            NavDisplay(
                backStack = backStack,
                onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                sceneStrategies = listOf(listDetailStrategy),
                entryProvider = { key ->
                    when (key) {
                        RecipesRoute -> NavEntry(
                            key,
                            metadata = ListDetailSceneStrategy.listPane(
                                detailPlaceholder = {
                                    AppContentStatePanel(
                                        AppContentState(
                                            AppContentStateKind.Empty,
                                            "Choose a recipe",
                                            "Select a recipe to see its details.",
                                        ),
                                    )
                                },
                            ),
                        ) {
                            RecipeLibraryScreen(
                                workflows = workflows,
                                refreshKey = recipeRefreshKey,
                                onOpenRecipe = { recipe -> backStack.add(RecipeDetailsRoute(recipe.id.toString())) },
                                onImport = {
                                    importCandidates.clear()
                                    completedSourceKeys.clear()
                                    backStack.add(RecipeImportRoute)
                                },
                            )
                        }

                        is RecipeDetailsRoute -> NavEntry(
                            key,
                            metadata = ListDetailSceneStrategy.detailPane(),
                        ) {
                            RecipeDetailScreen(
                                workflows = workflows,
                                recipeId = key.recipeId,
                                onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                                onSaved = { recipeRefreshKey++ },
                                onStartCooking = { recipe ->
                                    coordinator.start(recipe)
                                    backStack.clear()
                                    backStack.add(CookingRoute)
                                },
                                onDeleted = {
                                    recipeRefreshKey++
                                    if (backStack.size > 1) backStack.removeLastOrNull()
                                },
                            )
                        }

                        RecipeImportRoute -> NavEntry(key) {
                            RecipeImportScreen(
                                workflows = workflows,
                                candidates = importCandidates,
                                completedSourceKeys = completedSourceKeys.toSet(),
                                onCandidatesChanged = { candidates ->
                                    importCandidates.clear()
                                    importCandidates.addAll(candidates)
                                    completedSourceKeys.clear()
                                },
                                onReviewCandidate = { index -> backStack.add(RecipeImportReviewRoute(index)) },
                                onRequestPaprikaArchive = onRequestPaprikaArchive,
                                onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                            )
                        }

                        is RecipeImportReviewRoute -> NavEntry(key) {
                            val candidate = importCandidates.getOrNull(key.candidateIndex)
                            RecipeImportReviewScreen(
                                candidate = candidate,
                                workflows = workflows,
                                onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                                onSaved = {
                                    candidate?.sourceKey?.let { sourceKey ->
                                        if (sourceKey !in completedSourceKeys) completedSourceKeys.add(sourceKey)
                                    }
                                    recipeRefreshKey++
                                    if (backStack.size > 1) backStack.removeLastOrNull()
                                },
                            )
                        }

                        RecommendationsRoute -> NavEntry(key) {
                            RecommendationScreen(workflows = workflows) {
                                backStack.clear()
                                backStack.add(ShoppingRoute)
                            }
                        }

                        KitchenRoute -> NavEntry(key) {
                            KitchenSetupScreen(workflows = workflows)
                        }

                        CookingRoute -> NavEntry(key) {
                            CookingHome(coordinator, cookingPlatform, requestedEventId, { requestedEventId = null }) {
                                backStack.clear()
                                backStack.add(RecipesRoute)
                            }
                        }

                        ShoppingRoute -> NavEntry(key) {
                            ShoppingListScreen(workflows = workflows)
                        }

                        BackupRoute -> NavEntry(key) {
                            BackupRestoreScreen(
                                state = backupRestoreState,
                                onExport = exportBackup,
                                onImport = importBackup,
                                onReplaceAll = replaceAllBackupData,
                                onCancelRestore = cancelBackupRestore,
                            )
                        }

                        else -> error("Unknown app destination: $key")
                    }
                },
            )
        }
    }
}
