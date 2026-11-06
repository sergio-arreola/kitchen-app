package dev.kitchenapp.data.database

import app.cash.sqldelight.db.SqlDriver
import dev.kitchenapp.data.database.generated.KitchenDatabase

/** Opens the local schema on an already-created platform driver. */
object KitchenDatabaseFactory {
    fun open(driver: SqlDriver): KitchenDatabase {
        KitchenDatabase.Schema.create(driver)
        return KitchenDatabase(driver).also { it.recipeQueries.enableForeignKeys() }
    }
}
