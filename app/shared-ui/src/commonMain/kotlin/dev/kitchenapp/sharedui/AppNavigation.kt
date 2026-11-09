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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import dev.kitchenapp.core.application.RecipeImportCandidate
import dev.kitchenapp.core.application.RecipeLibraryUseCases
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

@Serializable
private data object RecipesRoute : NavKey

@Serializable
private data class RecipeDetailsRoute(val recipeId: String) : NavKey

@Serializable
private data object RecipeImportRoute : NavKey

@Serializable
private data class RecipeImportReviewRoute(val candidateIndex: Int) : NavKey

@Serializable
private data object KitchenRoute : NavKey

@Serializable
private data object CookingRoute : NavKey

@Serializable
private data object ShoppingRoute : NavKey

private val appNavigationSavedStateConfiguration = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(serializer = RecipesRoute.serializer())
            subclass(serializer = RecipeDetailsRoute.serializer())
            subclass(serializer = RecipeImportRoute.serializer())
            subclass(serializer = RecipeImportReviewRoute.serializer())
            subclass(serializer = KitchenRoute.serializer())
            subclass(serializer = CookingRoute.serializer())
            subclass(serializer = ShoppingRoute.serializer())
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
    AppDestination(KitchenRoute, "Kitchen", "K"),
    AppDestination(CookingRoute, "Cook", "C"),
    AppDestination(ShoppingRoute, "Shopping", "S"),
)

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun KitchenAppNavigation(
    workflows: RecipeLibraryUseCases,
    onRequestPaprikaArchive: ((String?) -> Unit) -> Unit,
) {
    val backStack = rememberNavBackStack(appNavigationSavedStateConfiguration, RecipesRoute)
    val currentRoute = backStack.lastOrNull()
    val selectedTopLevelRoute = when (currentRoute) {
        is RecipeDetailsRoute, RecipeImportRoute, is RecipeImportReviewRoute, RecipesRoute -> RecipesRoute
        KitchenRoute -> KitchenRoute
        CookingRoute -> CookingRoute
        ShoppingRoute -> ShoppingRoute
        else -> RecipesRoute
    }
    val importCandidates = remember { mutableStateListOf<RecipeImportCandidate>() }
    val completedSourceKeys = remember { mutableStateListOf<String>() }
    var recipeRefreshKey by remember { mutableIntStateOf(0) }
    val listDetailStrategy = rememberListDetailSceneStrategy<NavKey>()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val layoutType = if (maxWidth < 600.dp) {
            NavigationSuiteType.NavigationBar
        } else {
            NavigationSuiteType.NavigationRail
        }

        NavigationSuiteScaffold(
            layoutType = layoutType,
            navigationSuiteItems = {
                appDestinations.forEach { destination ->
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

                        KitchenRoute -> NavEntry(key) {
                            KitchenSetupScreen(workflows = workflows)
                        }

                        CookingRoute -> NavEntry(key) {
                            AppContentStatePanel(
                                state = AppContentState(
                                    kind = AppContentStateKind.Review,
                                    title = "Choose a recipe first",
                                    message = "Select a saved recipe before starting a cooking session.",
                                    actionLabel = "Browse recipes",
                                ),
                                onAction = {
                                    backStack.clear()
                                    backStack.add(RecipesRoute)
                                },
                            )
                        }

                        ShoppingRoute -> NavEntry(key) {
                            AppContentStatePanel(
                                AppContentState(
                                    kind = AppContentStateKind.Empty,
                                    title = "No shopping list yet",
                                    message = "Missing ingredients from selected recipes will appear here.",
                                ),
                            )
                        }

                        else -> error("Unknown app destination: $key")
                    }
                },
            )
        }
    }
}
