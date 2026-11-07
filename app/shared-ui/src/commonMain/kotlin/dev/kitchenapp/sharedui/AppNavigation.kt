package dev.kitchenapp.sharedui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

@Serializable
private data object RecipesRoute : NavKey

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

@Composable
internal fun KitchenAppNavigation() {
    val backStack = rememberNavBackStack(appNavigationSavedStateConfiguration, RecipesRoute)
    val currentRoute = backStack.lastOrNull()

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
                        selected = destination.route == currentRoute,
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
                entryProvider = { key ->
                    when (key) {
                        RecipesRoute -> NavEntry(key) {
                            AppContentStatePanel(
                                AppContentState(
                                    kind = AppContentStateKind.Empty,
                                    title = "No recipes yet",
                                    message = "Imported recipes will appear here.",
                                ),
                            )
                        }

                        KitchenRoute -> NavEntry(key) {
                            AppContentStatePanel(
                                AppContentState(
                                    kind = AppContentStateKind.Empty,
                                    title = "Your kitchen is ready to stock",
                                    message = "Add items from your pantry, refrigerator, freezer, or a custom location.",
                                ),
                            )
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
