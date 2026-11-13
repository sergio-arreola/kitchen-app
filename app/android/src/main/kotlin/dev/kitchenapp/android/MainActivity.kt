package dev.kitchenapp.android

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.ViewModelProvider
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
import dev.kitchenapp.core.application.ApplyKitchenBackupRestore
import dev.kitchenapp.core.application.DecodeKitchenBackupArchive
import dev.kitchenapp.core.application.ExportKitchenBackup
import dev.kitchenapp.core.application.KitchenBackupWorkflows
import dev.kitchenapp.core.application.PreviewKitchenBackupRestore
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
import dev.kitchenapp.data.database.AndroidKitchenDatabase
import dev.kitchenapp.data.database.SqlDelightKitchenRepository
import dev.kitchenapp.data.database.SqlDelightCookingRepository
import dev.kitchenapp.data.database.SqlDelightActiveCookingSessionRepository
import dev.kitchenapp.data.database.SqlDelightRecipeRepository
import dev.kitchenapp.data.database.SqlDelightShoppingListRepository
import dev.kitchenapp.data.database.SqlDelightKitchenBackupRestoreRepository
import dev.kitchenapp.data.database.SqlDelightKitchenBackupSnapshotRepository
import dev.kitchenapp.data.importers.DefaultRecipeImportGateway
import dev.kitchenapp.data.importers.DefaultRecipeImageLoader
import dev.kitchenapp.data.importers.KtorRecipePageFetcher
import dev.kitchenapp.data.importers.archive.AndroidContentResolverArchiveReader
import dev.kitchenapp.data.backup.KitchenBackupArchiveExporter
import dev.kitchenapp.data.backup.ValidatedKitchenBackupArchiveDecoder
import dev.kitchenapp.sharedui.KitchenApp
import dev.kitchenapp.sharedui.CookingWorkflows

class MainActivity : ComponentActivity() {
    private lateinit var cookingPlatform: AndroidCookingPlatform
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cookingPlatform.permissionResult(granted)
    }
    private var onPaprikaArchiveSelected: ((String?) -> Unit)? = null
    private val backupDocumentViewModel by lazy {
        ViewModelProvider(this)[BackupDocumentViewModel::class.java]
    }
    private val backupDocumentAccess by lazy {
        AndroidBackupDocumentAccess(applicationContext.contentResolver)
    }

    private val backupExportPicker = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        when {
            !backupDocumentViewModel.isExportPending -> backupDocumentViewModel.reportInterrupted("Export")
            uri == null -> backupDocumentViewModel.completeExport(null)
            else -> backupDocumentViewModel.writeExport(uri, backupDocumentAccess)
        }
    }

    private val backupImportPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        when {
            !backupDocumentViewModel.isImportPending -> backupDocumentViewModel.reportInterrupted("Import")
            uri == null -> backupDocumentViewModel.completeImport(Result.success(null))
            else -> backupDocumentViewModel.readImport(uri, backupDocumentAccess)
        }
    }

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
        cookingPlatform = AndroidCookingPlatform(this) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        cookingPlatform.create(intent)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        val database = AndroidKitchenDatabase.open(applicationContext)
        val recipes = SqlDelightRecipeRepository(database)
        val kitchen = SqlDelightKitchenRepository(database)
        val shoppingLists = SqlDelightShoppingListRepository(database)
        val cookingWorkflows = CookingWorkflows(SqlDelightActiveCookingSessionRepository(database), recipes, kitchen, SqlDelightCookingRepository(database, kitchen))
        val archiveReader = AndroidContentResolverArchiveReader(contentResolver)
        val backupSnapshots = SqlDelightKitchenBackupSnapshotRepository(database)
        val backupExporter = KitchenBackupArchiveExporter()
        val backupRestoreRepository = SqlDelightKitchenBackupRestoreRepository(
            database = database,
            snapshots = backupSnapshots,
            fingerprinter = backupExporter,
        )
        val backupWorkflows = KitchenBackupWorkflows(
            export = ExportKitchenBackup(backupSnapshots, backupExporter),
            decode = DecodeKitchenBackupArchive(ValidatedKitchenBackupArchiveDecoder()),
            previewRestore = PreviewKitchenBackupRestore(backupRestoreRepository),
            applyRestore = ApplyKitchenBackupRestore(backupRestoreRepository),
        )
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

        setContent {
            val backupDocumentResult = backupDocumentViewModel.result.collectAsState().value
            KitchenApp(
                workflows = workflows,
                cookingWorkflows = cookingWorkflows,
                cookingPlatform = cookingPlatform,
                onRequestPaprikaArchive = { onSelected ->
                    onPaprikaArchiveSelected = onSelected
                    paprikaArchivePicker.launch(arrayOf("*/*"))
                },
                backupWorkflows = backupWorkflows,
                backupDocumentResult = backupDocumentResult,
                onConsumeBackupDocumentResult = backupDocumentViewModel::consumeResult,
                onRequestBackupExport = { bytes ->
                    if (backupDocumentViewModel.beginExport(bytes)) {
                        runCatching { backupExportPicker.launch("kitchen-backup.kitchenbackup") }
                            .onFailure { error ->
                                backupDocumentViewModel.completeExport(Result.failure(error))
                            }
                    }
                },
                onRequestBackupImport = {
                    if (backupDocumentViewModel.beginImport()) {
                        runCatching { backupImportPicker.launch(arrayOf("*/*")) }
                            .onFailure { error ->
                                backupDocumentViewModel.completeImport(Result.failure(error))
                            }
                    }
                },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        cookingPlatform.start()
    }

    override fun onResume() {
        super.onResume()
        cookingPlatform.resume()
    }

    override fun onPause() {
        cookingPlatform.pause()
        super.onPause()
    }

    override fun onStop() {
        cookingPlatform.stop()
        super.onStop()
    }

    override fun onDestroy() {
        cookingPlatform.destroy()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        cookingPlatform.handleIntent(intent)
    }
}
