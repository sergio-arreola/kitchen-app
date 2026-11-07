package dev.kitchenapp.data.database

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.kitchenapp.data.database.generated.KitchenDatabase

/** Android SQLite driver using the app's private database directory. */
object AndroidKitchenDatabase {
    fun open(context: Context, name: String = "kitchen.db"): KitchenDatabase =
        KitchenDatabase(AndroidSqliteDriver(KitchenDatabase.Schema, context, name))
            .also { it.recipeQueries.enableForeignKeys() }
}
