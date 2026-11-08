package dev.kitchenapp.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.kitchenapp.core.application.AddCanonicalIngredient
import dev.kitchenapp.core.application.CreateRecipeCategory
import dev.kitchenapp.core.application.DeleteRecipe
import dev.kitchenapp.core.application.GetRecipe
import dev.kitchenapp.core.application.GetRecipeImage
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
import dev.kitchenapp.data.database.SqlDelightRecipeRepository
import dev.kitchenapp.data.importers.DefaultRecipeImportGateway
import dev.kitchenapp.data.importers.DefaultRecipeImageLoader
import dev.kitchenapp.data.importers.KtorRecipePageFetcher
import dev.kitchenapp.data.importers.archive.ZipFileArchiveReader
import dev.kitchenapp.sharedui.KitchenApp
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
    val archiveReader = ZipFileArchiveReader()
    val workflows = RecipeLibraryUseCases(
        searchRecipes = SearchRecipes(recipes),
        getRecipe = GetRecipe(recipes),
        getRecipeImage = GetRecipeImage(recipes),
        saveReviewedRecipe = SaveReviewedRecipe(recipes, kitchen),
        addCanonicalIngredient = AddCanonicalIngredient(kitchen),
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
    )

    Window(
        onCloseRequest = ::exitApplication,
        title = "Kitchen",
    ) {
        KitchenApp(
            workflows = workflows,
            onRequestPaprikaArchive = { onSelected ->
                val dialog = FileDialog(null as Frame?, "Choose Paprika archive", FileDialog.LOAD)
                dialog.isVisible = true
                val selected = dialog.file?.let { File(dialog.directory, it).absolutePath }
                onSelected(selected)
            },
        )
    }
}
