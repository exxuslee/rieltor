package com.rieltor.infrastructure.database.local

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.rieltor.infrastructure.database.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

@Database(
    entities = [
        ReceivedTelegramMessageEntity::class,
        PublishedRepostEntity::class,
        RepostPublicationEntity::class,
        TelegramRepostQueueEntity::class,
        TikTokPublishAttemptEntity::class,
        TikTokPublishThrottleEntity::class,
    ],
    version = 10,
    exportSchema = true,
)
internal abstract class RieltorDatabase : RoomDatabase() {
    abstract fun repostDao(): RepostDao
    abstract fun tikTokThrottleDao(): TikTokThrottleDao
    abstract fun repostQueueDao(): RepostQueueDao
}

class RoomDatabaseStore(path: Path) : AutoCloseable {
    internal val room: RieltorDatabase

    init {
        path.parent?.let(Files::createDirectories)
        room = Room.databaseBuilder<RieltorDatabase>(name = path.toAbsolutePath().toString())
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .addMigrations(*LEGACY_MIGRATIONS)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(connection: SQLiteConnection) {
                    connection.execSQL("PRAGMA busy_timeout=5000")
                    connection.execSQL("PRAGMA secure_delete=ON")
                }
            })
            .build()

        // Force opening and migration before the store becomes injectable.
        blocking { it.repostDao().receivedCount() }
        restrictFilePermissions(path)
    }

    internal fun <T> blocking(block: suspend (RieltorDatabase) -> T): T = runBlocking {
        block(room)
    }

    override fun close() = room.close()

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
}).toTypedArray()

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
    createRepostQueueTable(connection)
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
