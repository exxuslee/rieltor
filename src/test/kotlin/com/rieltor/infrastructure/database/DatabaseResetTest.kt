package com.rieltor.infrastructure.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.repository.CatalogRepository
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatabaseResetTest {
    @Test fun `version mismatch recreates all tables on upgrade and downgrade`() {
        val currentPath = Files.createTempDirectory("database-current-version").resolve("test.db")
        RoomDatabaseStore(currentPath).use { }
        val currentVersion = BundledSQLiteDriver().open(currentPath.toString()).use { connection ->
            connection.prepare("PRAGMA user_version").use { query ->
                assertTrue(query.step())
                query.getLong(0)
            }
        }
        for (version in listOf(1L, 16L, currentVersion + 1)) {
            val path = Files.createTempDirectory("database-reset").resolve("test.db")
            BundledSQLiteDriver().open(path.toString()).use { connection ->
                connection.execSQL("CREATE TABLE listings (id INTEGER PRIMARY KEY, obsolete TEXT)")
                connection.execSQL("INSERT INTO listings VALUES (1, 'old listing')")
                connection.execSQL("CREATE TABLE obsolete_queue (id INTEGER PRIMARY KEY)")
                connection.execSQL("INSERT INTO obsolete_queue VALUES (1)")
                connection.execSQL("PRAGMA user_version=$version")
            }
            RoomDatabaseStore(path).use { db ->
                val repository = CatalogRepository(db)
                assertTrue(repository.listings().isEmpty())
                assertTrue(repository.incoming().isEmpty())
            }
            BundledSQLiteDriver().open(path.toString()).use { connection ->
                connection.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name != 'room_master_table'").use { query ->
                    val tables = buildSet { while (query.step()) add(query.getText(0)) }
                    assertEquals(setOf("adsTab", "incomeTab", "repostTab"), tables)
                }
                connection.prepare("PRAGMA integrity_check").use {
                    assertTrue(it.step()); assertEquals("ok", it.getText(0))
                }
            }
        }
    }
}
