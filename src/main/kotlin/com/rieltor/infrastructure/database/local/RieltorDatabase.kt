package com.rieltor.infrastructure.database.local

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.rieltor.infrastructure.database.model.AdEntity
import com.rieltor.infrastructure.database.model.IncomingEntity
import com.rieltor.infrastructure.database.model.RepostEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

@Database(
    entities = [IncomingEntity::class, AdEntity::class, RepostEntity::class],
    version = 29,
    exportSchema = true,
)
internal abstract class RieltorDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao
}

// Migrations up to 22 intentionally keep the historical table name `incoming_telegram_messages`:
// it is what the schema was called at those versions. The table becomes `incomeTab` in 22 -> 23.
private val migrations = arrayOf(
    object : Migration(17, 18) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE listings ADD COLUMN rawText TEXT NOT NULL DEFAULT ''")
        }
    },
    object : Migration(18, 19) {
        override fun migrate(connection: SQLiteConnection) {
            // Keep an old successful flag representable before its redundant column is removed.
            connection.execSQL("UPDATE listings SET tiktokRepostedAt = updatedAt WHERE tiktokReposted = 1 AND tiktokRepostedAt IS NULL")
            connection.execSQL("UPDATE listings SET threadsRepostedAt = updatedAt WHERE threadsReposted = 1 AND threadsRepostedAt IS NULL")
            connection.execSQL("ALTER TABLE listings DROP COLUMN tiktokReposted")
            connection.execSQL("ALTER TABLE listings DROP COLUMN threadsReposted")
            connection.execSQL("ALTER TABLE listings DROP COLUMN tiktokPublishId")
            connection.execSQL("ALTER TABLE listings DROP COLUMN threadsPublishId")
        }
    },
    object : Migration(19, 20) {
        override fun migrate(connection: SQLiteConnection) = migrateAdIds(connection)
    },
    object : Migration(20, 21) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("DROP INDEX index_incoming_telegram_messages_sourceKey")
            connection.execSQL("DROP INDEX index_listings_sourceKey")
            connection.execSQL("ALTER TABLE incoming_telegram_messages DROP COLUMN sourceKey")
            connection.execSQL("ALTER TABLE listings DROP COLUMN sourceKey")
        }
    },
    object : Migration(21, 22) {
        override fun migrate(connection: SQLiteConnection) {
            // Photos of the Telegram post itself; a listing no longer needs a Google Drive link.
            connection.execSQL(
                "ALTER TABLE incoming_telegram_messages ADD COLUMN sourcePhotos TEXT NOT NULL DEFAULT '[]'"
            )
        }
    },
    object : Migration(22, 23) {
        override fun migrate(connection: SQLiteConnection) {
            // Room derives index names from the table name, so they are recreated after the rename.
            connection.execSQL("DROP INDEX index_incoming_telegram_messages_chatId_messageId")
            connection.execSQL("DROP INDEX index_incoming_telegram_messages_status_verifyAfter")
            connection.execSQL("ALTER TABLE incoming_telegram_messages RENAME TO incomeTab")
            // The download lease was redundant: ownership is guarded by status + revision in one worker.
            connection.execSQL("ALTER TABLE incomeTab DROP COLUMN leaseToken")
            connection.execSQL("ALTER TABLE incomeTab DROP COLUMN leaseUntil")
            connection.execSQL("CREATE UNIQUE INDEX index_incomeTab_chatId_messageId ON incomeTab(chatId, messageId)")
            connection.execSQL("CREATE INDEX index_incomeTab_status_verifyAfter ON incomeTab(status, verifyAfter)")
        }
    },
    object : Migration(23, 24) {
        override fun migrate(connection: SQLiteConnection) {
            // Keep the compact sender identity used for deduplication before removing the TDLib dump.
            connection.execSQL("ALTER TABLE incomeTab ADD COLUMN senderIdentity TEXT")
            connection.prepare("SELECT id, rawMessage FROM incomeTab").use { query ->
                while (query.step()) {
                    val sender = com.rieltor.domain.model.senderFromRaw(query.getText(1)) ?: continue
                    connection.prepare("UPDATE incomeTab SET senderIdentity=? WHERE id=?").use { update ->
                        update.bindText(1, sender)
                        update.bindLong(2, query.getLong(0))
                        update.step()
                    }
                }
            }
            connection.execSQL("ALTER TABLE incomeTab DROP COLUMN rawMessage")
        }
    },
    object : Migration(24, 25) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE incomeTab DROP COLUMN receivedAt")
        }
    },
    object : Migration(25, 26) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE listings RENAME TO adsTab")
            // Room index names include the table name.
            connection.execSQL("DROP INDEX index_listings_adId")
            connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_adsTab_adId` ON `adsTab` (`adId`)")
            connection.execSQL("DROP INDEX index_listings_chatId_messageId")
            connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_adsTab_chatId_messageId` ON `adsTab` (`chatId`, `messageId`)")
            connection.execSQL("DROP INDEX index_listings_status_sourceCreatedAt_id")
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_adsTab_status_sourceCreatedAt_id` ON `adsTab` (`status`, `sourceCreatedAt`, `id`)")
            connection.execSQL("DROP INDEX index_listings_status_location_typeOfRealty_currency_price")
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_adsTab_status_location_typeOfRealty_currency_price` ON `adsTab` (`status`, `location`, `typeOfRealty`, `currency`, `price`)")
            connection.execSQL("DROP INDEX index_listings_status_tiktokStatus_sourceCreatedAt")
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_adsTab_status_tiktokStatus_sourceCreatedAt` ON `adsTab` (`status`, `tiktokStatus`, `sourceCreatedAt`)")
            connection.execSQL("DROP INDEX index_listings_status_threadsStatus_sourceCreatedAt")
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_adsTab_status_threadsStatus_sourceCreatedAt` ON `adsTab` (`status`, `threadsStatus`, `sourceCreatedAt`)")
        }
    },
    object : Migration(26, 27) {
        override fun migrate(connection: SQLiteConnection) {
            // Recover user IDs only; channel identities are not Telegram user IDs.
            connection.prepare("SELECT id, senderIdentity FROM incomeTab WHERE userId IS NULL AND senderIdentity IS NOT NULL").use { query ->
                while (query.step()) {
                    val userId = query.getText(1).toLongOrNull()?.takeIf { it > 0 } ?: continue
                    connection.prepare("UPDATE incomeTab SET userId=? WHERE id=?").use { update ->
                        update.bindLong(1, userId)
                        update.bindLong(2, query.getLong(0))
                        update.step()
                    }
                }
            }
            connection.execSQL("ALTER TABLE incomeTab DROP COLUMN senderIdentity")
        }
    },
    object : Migration(27, 28) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("""
                CREATE TABLE repostTab (
                    id INTEGER NOT NULL PRIMARY KEY,
                    tiktokRepostedAt INTEGER, threadsRepostedAt INTEGER,
                    tiktokStatus TEXT NOT NULL, threadsStatus TEXT NOT NULL,
                    tiktokState TEXT NOT NULL, threadsState TEXT NOT NULL,
                    FOREIGN KEY(id) REFERENCES adsTab(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
            """.trimIndent())
            val columns = "id, tiktokRepostedAt, threadsRepostedAt, tiktokStatus, threadsStatus, tiktokState, threadsState"
            connection.execSQL("INSERT INTO repostTab ($columns) SELECT $columns FROM adsTab")
            connection.execSQL("DROP INDEX index_adsTab_status_tiktokStatus_sourceCreatedAt")
            connection.execSQL("DROP INDEX index_adsTab_status_threadsStatus_sourceCreatedAt")
            columns.split(", ").drop(1).forEach { column ->
                connection.execSQL("ALTER TABLE adsTab DROP COLUMN $column")
            }
            connection.execSQL("CREATE INDEX index_repostTab_tiktokStatus ON repostTab(tiktokStatus)")
            connection.execSQL("CREATE INDEX index_repostTab_threadsStatus ON repostTab(threadsStatus)")
        }
    },
    object : Migration(28, 29) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("DROP INDEX index_adsTab_status_sourceCreatedAt_id")
            connection.execSQL("ALTER TABLE adsTab RENAME COLUMN sourceCreatedAt TO timestamp")
            listOf("createdAt", "updatedAt", "publishedAt").forEach { column ->
                connection.execSQL("ALTER TABLE adsTab DROP COLUMN $column")
            }
            connection.execSQL("CREATE INDEX index_adsTab_status_timestamp_id ON adsTab(status, timestamp, id)")
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
