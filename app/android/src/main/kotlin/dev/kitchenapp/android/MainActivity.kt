package dev.kitchenapp.android

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import dev.kitchenapp.core.application.AddCanonicalIngredient
import dev.kitchenapp.core.application.ConfigureKitchen
import dev.kitchenapp.core.application.CreateRecipeCategory
import dev.kitchenapp.core.application.DeleteCanonicalIngredient
import dev.kitchenapp.core.application.DeleteRecipe
import dev.kitchenapp.core.application.EditInventory
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
import dev.kitchenapp.data.database.AndroidKitchenDatabase
import dev.kitchenapp.data.database.SqlDelightKitchenRepository
import dev.kitchenapp.data.database.SqlDelightRecipeRepository
import dev.kitchenapp.data.importers.DefaultRecipeImportGateway
import dev.kitchenapp.data.importers.DefaultRecipeImageLoader
import dev.kitchenapp.data.importers.KtorRecipePageFetcher
import dev.kitchenapp.data.importers.archive.AndroidContentResolverArchiveReader
import dev.kitchenapp.sharedui.KitchenApp

class MainActivity : ComponentActivity() {
    private var onPaprikaArchiveSelected: ((String?) -> Unit)? = null

    private val paprikaArchivePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        val callback = onPaprikaArchiveSelected
        onPaprikaArchiveSelected = null
        callback?.invoke(uri?.toString())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        val database = AndroidKitchenDatabase.open(applicationContext)
        val recipes = SqlDelightRecipeRepository(database)
        val kitchen = SqlDelightKitchenRepository(database)
        val archiveReader = AndroidContentResolverArchiveReader(contentResolver)
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
        )

        setContent {
            KitchenApp(
                workflows = workflows,
                onRequestPaprikaArchive = { onSelected ->
                    onPaprikaArchiveSelected = onSelected
                    paprikaArchivePicker.launch(arrayOf("*/*"))
                },
            )
        }
    }
}
