package dev.kitchenapp.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.kitchenapp.core.application.AddCanonicalIngredient
import dev.kitchenapp.core.application.ConfigureKitchen
import dev.kitchenapp.core.application.CreateRecipeCategory
import dev.kitchenapp.core.application.DeleteCanonicalIngredient
import dev.kitchenapp.core.application.DeleteRecipe
import dev.kitchenapp.core.application.EditInventory
import dev.kitchenapp.core.application.GetRecipe
import dev.kitchenapp.core.application.GetRecipeImage
import dev.kitchenapp.core.application.GetShoppingList
import dev.kitchenapp.core.application.GetSuggestions
import dev.kitchenapp.core.application.CreateShoppingList
import dev.kitchenapp.core.application.SetShoppingListItemChecked
import dev.kitchenapp.core.application.ClearShoppingList
import dev.kitchenapp.core.application.ListRecipeCategories
import dev.kitchenapp.core.application.LoadRecipeImage
import dev.kitchenapp.core.application.PreviewRecipeImport
import dev.kitchenapp.core.application.RecipeLibraryUseCases
import dev.kitchenapp.core.application.SaveReviewedRecipe
import dev.kitchenapp.core.application.RenameRecipeCategory
import dev.kitchenapp.core.application.RemoveRecipeCategory
import dev.kitchenapp.core.application.SearchRecipes
import dev.kitchenapp.data.database.JvmKitchenDatabase
import dev.kitchenapp.data.database.SqlDelightKitchenRepository
import dev.kitchenapp.data.database.SqlDelightCookingRepository
import dev.kitchenapp.data.database.SqlDelightActiveCookingSessionRepository
import dev.kitchenapp.data.database.SqlDelightRecipeRepository
import dev.kitchenapp.data.database.SqlDelightShoppingListRepository
import dev.kitchenapp.data.importers.DefaultRecipeImportGateway
import dev.kitchenapp.data.importers.DefaultRecipeImageLoader
import dev.kitchenapp.data.importers.KtorRecipePageFetcher
import dev.kitchenapp.data.importers.archive.ZipFileArchiveReader
import dev.kitchenapp.sharedui.CookingWorkflows
import dev.kitchenapp.sharedui.NoCookingPlatform
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

fun main() = application {
    val databaseDirectory = File(System.getProperty("user.home"), ".kitchen-app")
    check(databaseDirectory.isDirectory || databaseDirectory.mkdirs()) {
        "Could not create local Kitchen database directory"
    }
    val database = JvmKitchenDatabase.open(File(databaseDirectory, "kitchen.db").absolutePath)
    val recipes = SqlDelightRecipeRepository(database)
    val kitchen = SqlDelightKitchenRepository(database)
    val shoppingLists = SqlDelightShoppingListRepository(database)
    val cookingWorkflows = CookingWorkflows(SqlDelightActiveCookingSessionRepository(database), recipes, kitchen, SqlDelightCookingRepository(database, kitchen))
    val archiveReader = ZipFileArchiveReader()
    val workflows = RecipeLibraryUseCases(
        searchRecipes = SearchRecipes(recipes),
        getRecipe = GetRecipe(recipes),
        getRecipeImage = GetRecipeImage(recipes),
        saveReviewedRecipe = SaveReviewedRecipe(recipes, kitchen),
        addCanonicalIngredient = AddCanonicalIngredient(kitchen),
        deleteCanonicalIngredient = DeleteCanonicalIngredient(recipes, kitchen),
        editInventory = EditInventory(kitchen),
        configureKitchen = ConfigureKitchen(kitchen),
        previewRecipeImport = PreviewRecipeImport(
            gateway = DefaultRecipeImportGateway(
                archiveReader = archiveReader,
                pageFetcher = KtorRecipePageFetcher(),
            ),
            recipes = recipes,
            kitchen = kitchen,
        ),
        deleteRecipe = DeleteRecipe(recipes),
        listRecipeCategories = ListRecipeCategories(recipes),
        createRecipeCategory = CreateRecipeCategory(recipes),
        renameRecipeCategory = RenameRecipeCategory(recipes),
        removeRecipeCategory = RemoveRecipeCategory(recipes),
        loadRecipeImage = LoadRecipeImage(DefaultRecipeImageLoader(archiveReader)),
        getSuggestions = GetSuggestions(recipes, kitchen),
        createShoppingList = CreateShoppingList(kitchen, shoppingLists),
        getShoppingList = GetShoppingList(shoppingLists),
        setShoppingListItemChecked = SetShoppingListItemChecked(shoppingLists),
        clearShoppingList = ClearShoppingList(shoppingLists),
    )

    Window(
        onCloseRequest = ::exitApplication,
        title = "Kitchen",
    ) {
        DesktopWorkspace(
            workflows = workflows,
            cookingWorkflows = cookingWorkflows,
            cookingPlatform = NoCookingPlatform,
            onRequestPaprikaArchive = { onSelected ->
                val dialog = FileDialog(null as Frame?, "Choose Paprika archive", FileDialog.LOAD)
                dialog.isVisible = true
                val selected = dialog.file?.let { File(dialog.directory, it).absolutePath }
                onSelected(selected)
            },
        )
    }
}
