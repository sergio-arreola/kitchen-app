package dev.kitchenapp.data.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.kitchenapp.data.database.generated.KitchenDatabase

/** File-backed SQLite driver for desktop and JVM tooling. */
object JvmKitchenDatabase {
    fun open(path: String): KitchenDatabase {
        val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
        return KitchenDatabaseFactory.open(driver)
    }
}
