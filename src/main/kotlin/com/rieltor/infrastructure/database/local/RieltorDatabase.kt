package com.rieltor.infrastructure.database.local

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.rieltor.infrastructure.database.model.IncomingEntity
import com.rieltor.infrastructure.database.model.ListingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

@Database(
    entities = [IncomingEntity::class, ListingEntity::class], version = 18,
    exportSchema = true,
)
internal abstract class RieltorDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao
}

private val migrations = arrayOf(
    object : Migration(17, 18) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE listings ADD COLUMN rawText TEXT NOT NULL DEFAULT ''")
        }
    },
)

class RoomDatabaseStore(
    path: Path,
    val settings: com.rieltor.infrastructure.config.JsonSettingsStore = com.rieltor.infrastructure.config.JsonSettingsStore(
        path.resolveSibling("settings.json")
    ),
    private val ownsSettings: Boolean = true
) : AutoCloseable {
    internal val room: RieltorDatabase

    init {
        path.parent?.let(Files::createDirectories)
        room = Room.databaseBuilder<RieltorDatabase>(name = path.toAbsolutePath().toString())
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .addMigrations(*migrations)
            .fallbackToDestructiveMigration(true)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(connection: SQLiteConnection) {
                    connection.execSQL("PRAGMA busy_timeout=5000")
                    connection.execSQL("PRAGMA secure_delete=ON")
                }
            })
            .build()

        // Force opening and schema validation before the store becomes injectable.
        blocking { it.catalogDao().count() }
        restrictFilePermissions(path)
    }

    internal fun <T> blocking(block: suspend (RieltorDatabase) -> T): T = runBlocking {
        block(room)
    }

    override fun close() {
        room.close(); if (ownsSettings) settings.close()
    }

    private fun restrictFilePermissions(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }
}
