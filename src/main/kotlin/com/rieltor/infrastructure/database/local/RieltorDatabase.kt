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
    entities = [IncomingEntity::class, ListingEntity::class], version = 14,
    exportSchema = true,
)
internal abstract class RieltorDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao
}

class RoomDatabaseStore(path: Path, val settings: com.rieltor.infrastructure.config.JsonSettingsStore = com.rieltor.infrastructure.config.JsonSettingsStore(path.resolveSibling("settings.json")), private val ownsSettings: Boolean = true) : AutoCloseable {
    internal val room: RieltorDatabase

    init {
        path.parent?.let(Files::createDirectories)
        if (Files.exists(path) && Files.size(path) > 0) {
            BundledSQLiteDriver().open(path.toAbsolutePath().toString()).use { connection ->
                val version = connection.prepare("PRAGMA user_version").use { it.step(); it.getLong(0) }
                if (version in 1..11) {
                    val backup = path.resolveSibling("${path.fileName}.backup-${System.currentTimeMillis()}").toAbsolutePath()
                    connection.prepare("VACUUM INTO ?").use { it.bindText(1, backup.toString()); it.step() }
                }
            }
        }
        room = Room.databaseBuilder<RieltorDatabase>(name = path.toAbsolutePath().toString())
            .setDriver(BundledSQLiteDriver())
//            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .setQueryCoroutineContext(Dispatchers.IO)
            .addMigrations(*LEGACY_MIGRATIONS, CatalogMigration(settings, path), SimplifyIncomingTelegramMessagesMigration, RemoveListingMetadataMigration)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(connection: SQLiteConnection) {
                    connection.execSQL("PRAGMA busy_timeout=5000")
                    connection.execSQL("PRAGMA secure_delete=ON")
                }
            })
            .build()

        // Force opening and migration before the store becomes injectable.
        blocking { it.catalogDao().count() }
        restrictFilePermissions(path)
    }

    internal fun <T> blocking(block: suspend (RieltorDatabase) -> T): T = runBlocking {
        block(room)
    }

    override fun close() { room.close(); if (ownsSettings) settings.close() }

    private fun restrictFilePermissions(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }
}

private val LEGACY_MIGRATIONS = ((1..7).map { startVersion ->
    object : Migration(startVersion, 9) {
        override fun migrate(connection: SQLiteConnection) {
            if (startVersion < 7) {
                connection.execSQL(
                    "ALTER TABLE received_telegram_messages " +
                        "ADD COLUMN google_drive_links TEXT NOT NULL DEFAULT '[]'"
                )
            }
            createCurrentTables(connection)
            if (startVersion < 6) {
                connection.execSQL(
                    """INSERT OR IGNORE INTO repost_publications(
                        telegram_update_id, destination, message_thread_id,
                        normalized_price, normalized_address, status,
                        publish_id, created_at, updated_at
                    )
                    SELECT telegram_update_id, 'TIKTOK', message_thread_id,
                           normalized_price, normalized_address, 'PUBLISHED',
                           publish_id, published_at, published_at
                    FROM published_reposts"""
                )
            }
            // Room serializes write transactions, so duplicate reservations remain atomic
            // without the former SQLite-only partial unique index.
            connection.execSQL("DROP INDEX IF EXISTS uq_active_telegram_repost_key")
            connection.execSQL("DROP INDEX IF EXISTS uq_active_repost_destination_key")
            rebuildAsRoomSchema(connection)
        }
    }
} + object : Migration(8, 10) {
    override fun migrate(connection: SQLiteConnection) {
        createRepostQueueTable(connection)
        dropCredentialTables(connection)
    }
} + object : Migration(9, 10) {
    override fun migrate(connection: SQLiteConnection) {
        dropCredentialTables(connection)
    }
} + object : Migration(10, 11) {
    override fun migrate(connection: SQLiteConnection) {
        createTikTokTrackedPublishesTable(connection)
    }
}).toTypedArray()

/** Removes inbox fields that were write-only or duplicated data derived during parsing. */
private object SimplifyIncomingTelegramMessagesMigration : Migration(12, 13) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """CREATE TABLE incoming_telegram_messages_v13 (
                id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                chatId INTEGER NOT NULL,
                messageId INTEGER,
                messageThreadId INTEGER NOT NULL,
                mediaAlbumId INTEGER NOT NULL,
                groupKey TEXT NOT NULL,
                rawMessage TEXT NOT NULL,
                rawText TEXT NOT NULL,
                sourceCreatedAt INTEGER NOT NULL,
                sourceEditedAt INTEGER NOT NULL,
                receivedAt INTEGER NOT NULL,
                contentHash TEXT NOT NULL,
                revision INTEGER NOT NULL,
                verifyAfter INTEGER NOT NULL,
                verifiedAt INTEGER,
                status TEXT NOT NULL,
                mediaManifest TEXT NOT NULL,
                attemptCount INTEGER NOT NULL,
                nextAttemptAt INTEGER NOT NULL,
                leaseToken TEXT,
                leaseUntil INTEGER NOT NULL
            )"""
        )
        connection.execSQL(
            """INSERT INTO incoming_telegram_messages_v13 (
                id, chatId, messageId, messageThreadId, mediaAlbumId, groupKey,
                rawMessage, rawText, sourceCreatedAt, sourceEditedAt, receivedAt,
                contentHash, revision, verifyAfter, verifiedAt, status, mediaManifest,
                attemptCount, nextAttemptAt, leaseToken, leaseUntil
            ) SELECT
                id, chatId, messageId, messageThreadId, mediaAlbumId, groupKey,
                rawMessage, rawText, sourceCreatedAt, sourceEditedAt, receivedAt,
                contentHash, revision, verifyAfter, verifiedAt, status, mediaManifest,
                attemptCount, nextAttemptAt, leaseToken, leaseUntil
            FROM incoming_telegram_messages"""
        )
        connection.execSQL("DROP TABLE incoming_telegram_messages")
        connection.execSQL("ALTER TABLE incoming_telegram_messages_v13 RENAME TO incoming_telegram_messages")
        connection.execSQL("CREATE UNIQUE INDEX index_incoming_telegram_messages_chatId_messageId ON incoming_telegram_messages(chatId, messageId)")
        connection.execSQL("CREATE INDEX index_incoming_telegram_messages_groupKey ON incoming_telegram_messages(groupKey)")
        connection.execSQL("CREATE INDEX index_incoming_telegram_messages_status_verifyAfter ON incoming_telegram_messages(status, verifyAfter)")
    }
}

/** Removes parser and legacy-migration metadata that is not used by the catalog. */
private object RemoveListingMetadataMigration : Migration(13, 14) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("DROP INDEX IF EXISTS index_listings_legacyUpdateId")
        connection.execSQL(
            """CREATE TABLE listings_v14 (
                id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                groupKey TEXT NOT NULL, chatId INTEGER NOT NULL, messageId INTEGER,
                messageThreadId INTEGER NOT NULL, mediaAlbumId INTEGER NOT NULL,
                rawMessage TEXT NOT NULL, sourceRevision TEXT NOT NULL, title TEXT NOT NULL,
                description TEXT NOT NULL, location TEXT, address TEXT, district TEXT,
                typeOfRealty TEXT, transactionType TEXT NOT NULL, tags TEXT NOT NULL,
                primeParams TEXT NOT NULL, secondaryParams TEXT NOT NULL,
                governmentPrograms TEXT NOT NULL, governmentProgramsKnown INTEGER NOT NULL,
                googleDriveUrl TEXT, googleDriveUrls TEXT NOT NULL, price INTEGER, currency TEXT,
                pricePeriod TEXT NOT NULL, areaM2 REAL, landAreaSotka REAL, rooms INTEGER,
                floor INTEGER, totalFloors INTEGER, photos TEXT NOT NULL, coverPhotoId TEXT,
                status TEXT NOT NULL, sourceCreatedAt INTEGER NOT NULL, receivedAt INTEGER NOT NULL,
                cdt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, publishedAt INTEGER,
                tiktokReposted INTEGER NOT NULL, threadsReposted INTEGER NOT NULL,
                tiktokRepostedAt INTEGER, threadsRepostedAt INTEGER, tiktokStatus TEXT NOT NULL,
                threadsStatus TEXT NOT NULL, tiktokPublishId TEXT, threadsPublishId TEXT,
                tiktokState TEXT NOT NULL, threadsState TEXT NOT NULL
            )"""
        )
        connection.execSQL(
            """INSERT INTO listings_v14 SELECT
                id, groupKey, chatId, messageId, messageThreadId, mediaAlbumId, rawMessage,
                sourceRevision, title, description, location, address, district, typeOfRealty,
                transactionType, tags, primeParams, secondaryParams, governmentPrograms,
                governmentProgramsKnown, googleDriveUrl, googleDriveUrls, price, currency,
                pricePeriod, areaM2, landAreaSotka, rooms, floor, totalFloors, photos,
                coverPhotoId, status, sourceCreatedAt, receivedAt, cdt, updatedAt, publishedAt,
                tiktokReposted, threadsReposted, tiktokRepostedAt, threadsRepostedAt,
                tiktokStatus, threadsStatus, tiktokPublishId, threadsPublishId, tiktokState,
                threadsState FROM listings"""
        )
        connection.execSQL("DROP TABLE listings")
        connection.execSQL("ALTER TABLE listings_v14 RENAME TO listings")
        connection.execSQL("CREATE UNIQUE INDEX index_listings_groupKey ON listings(groupKey)")
        connection.execSQL("CREATE UNIQUE INDEX index_listings_chatId_messageId ON listings(chatId, messageId)")
        connection.execSQL("CREATE INDEX index_listings_status_sourceCreatedAt_id ON listings(status, sourceCreatedAt, id)")
        connection.execSQL("CREATE INDEX index_listings_status_location_typeOfRealty_currency_price ON listings(status, location, typeOfRealty, currency, price)")
        connection.execSQL("CREATE INDEX index_listings_status_tiktokStatus_sourceCreatedAt ON listings(status, tiktokStatus, sourceCreatedAt)")
        connection.execSQL("CREATE INDEX index_listings_status_threadsStatus_sourceCreatedAt ON listings(status, threadsStatus, sourceCreatedAt)")
    }
}

private fun createCurrentTables(connection: SQLiteConnection) {
    connection.execSQL(
        """CREATE TABLE IF NOT EXISTS received_telegram_messages (
            telegram_update_id INTEGER NOT NULL PRIMARY KEY, chat_id INTEGER NOT NULL,
            message_thread_id INTEGER NOT NULL, normalized_price TEXT, normalized_address TEXT,
            caption TEXT, google_drive_links TEXT NOT NULL DEFAULT '[]', status TEXT NOT NULL,
            duplicate_of_update_id INTEGER, error TEXT,
            received_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
        )"""
    )
    connection.execSQL(
        """CREATE TABLE IF NOT EXISTS published_reposts (
            telegram_update_id INTEGER NOT NULL PRIMARY KEY, message_thread_id INTEGER NOT NULL,
            normalized_price TEXT, normalized_address TEXT, publish_id TEXT NOT NULL,
            published_at INTEGER NOT NULL
        )"""
    )
    connection.execSQL(
        """CREATE TABLE IF NOT EXISTS repost_publications (
            telegram_update_id INTEGER NOT NULL, destination TEXT NOT NULL, message_thread_id INTEGER NOT NULL,
            normalized_price TEXT, normalized_address TEXT, status TEXT NOT NULL,
            duplicate_of_update_id INTEGER, publish_id TEXT, error TEXT, created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL, PRIMARY KEY(telegram_update_id, destination)
        )"""
    )
    connection.execSQL(
        """CREATE TABLE IF NOT EXISTS tiktok_publish_attempts (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, attempted_at INTEGER NOT NULL
        )"""
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS ix_tiktok_publish_attempts_time " +
            "ON tiktok_publish_attempts(attempted_at)"
    )
    connection.execSQL(
        """CREATE TABLE IF NOT EXISTS tiktok_publish_throttle (
            id INTEGER NOT NULL PRIMARY KEY, blocked_until INTEGER NOT NULL DEFAULT 0
        )"""
    )
    createTikTokTrackedPublishesTable(connection)
    createRepostQueueTable(connection)
}

private fun createTikTokTrackedPublishesTable(connection: SQLiteConnection) {
    connection.execSQL(
        """CREATE TABLE IF NOT EXISTS tiktok_tracked_publishes (
            publish_id TEXT NOT NULL PRIMARY KEY, mode TEXT NOT NULL, created_at INTEGER NOT NULL,
            last_status TEXT, updated_at INTEGER NOT NULL
        )"""
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS ix_tiktok_tracked_publishes_created_at " +
            "ON tiktok_tracked_publishes(created_at)"
    )
}

private fun createRepostQueueTable(connection: SQLiteConnection) {
    connection.execSQL(
        """CREATE TABLE IF NOT EXISTS telegram_repost_queue (
            telegram_update_id INTEGER NOT NULL PRIMARY KEY, chat_id INTEGER NOT NULL,
            message_thread_id INTEGER NOT NULL, caption TEXT, google_drive_links TEXT NOT NULL,
            normalized_price TEXT, normalized_address TEXT, telegram_photo_paths TEXT NOT NULL,
            enqueued_at INTEGER NOT NULL, claimed INTEGER NOT NULL DEFAULT 0
        )"""
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS ix_telegram_repost_queue_fifo " +
            "ON telegram_repost_queue(enqueued_at)"
    )
}

private fun rebuildAsRoomSchema(connection: SQLiteConnection) {
    connection.execSQL("DROP INDEX IF EXISTS ix_tiktok_publish_attempts_time")
    val tables = listOf(
        "received_telegram_messages",
        "published_reposts",
        "repost_publications",
        "tiktok_publish_attempts",
        "tiktok_publish_throttle",
    )
    tables.forEach { table -> connection.execSQL("ALTER TABLE $table RENAME TO ${table}_room_legacy") }
    createCurrentTables(connection)
    connection.execSQL(
        """INSERT INTO received_telegram_messages SELECT telegram_update_id, chat_id, message_thread_id,
            normalized_price, normalized_address, caption, google_drive_links, status,
            duplicate_of_update_id, error, received_at, updated_at
            FROM received_telegram_messages_room_legacy"""
    )
    connection.execSQL(
        """INSERT INTO published_reposts SELECT telegram_update_id, message_thread_id,
            normalized_price, normalized_address, publish_id, published_at
            FROM published_reposts_room_legacy"""
    )
    connection.execSQL(
        """INSERT INTO repost_publications SELECT telegram_update_id, destination, message_thread_id,
            normalized_price, normalized_address, status, duplicate_of_update_id, publish_id, error,
            created_at, updated_at FROM repost_publications_room_legacy"""
    )
    connection.execSQL(
        "INSERT INTO tiktok_publish_attempts(id, attempted_at) SELECT id, attempted_at FROM tiktok_publish_attempts_room_legacy"
    )
    connection.execSQL(
        "INSERT INTO tiktok_publish_throttle SELECT id, blocked_until FROM tiktok_publish_throttle_room_legacy"
    )
    tables.forEach { table -> connection.execSQL("DROP TABLE ${table}_room_legacy") }
    dropCredentialTables(connection)
}

private fun dropCredentialTables(connection: SQLiteConnection) {
    connection.execSQL("DROP TABLE IF EXISTS app_secrets")
    connection.execSQL("DROP TABLE IF EXISTS tiktok_tokens")
    connection.execSQL("DROP TABLE IF EXISTS google_drive_tokens")
    connection.execSQL("DROP TABLE IF EXISTS threads_tokens")
}
