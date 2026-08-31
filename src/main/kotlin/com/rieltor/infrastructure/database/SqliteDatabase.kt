package com.rieltor.infrastructure.database

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.sql.Connection
import java.sql.DriverManager

class SqliteDatabase(private val path: Path) {
    init {
        Class.forName("org.sqlite.JDBC")
        path.parent?.let(Files::createDirectories)
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("PRAGMA busy_timeout=5000")
                statement.execute("PRAGMA secure_delete=ON")
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS app_secrets (
                        name TEXT PRIMARY KEY,
                        value TEXT NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS tiktok_tokens (
                        open_id TEXT PRIMARY KEY,
                        access_token TEXT NOT NULL,
                        refresh_token TEXT NOT NULL,
                        access_token_expires_at INTEGER NOT NULL,
                        refresh_token_expires_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS google_drive_tokens (
                        id INTEGER PRIMARY KEY CHECK (id = 1),
                        access_token TEXT NOT NULL,
                        refresh_token TEXT NOT NULL,
                        access_token_expires_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS threads_tokens (
                        user_id TEXT PRIMARY KEY,
                        access_token TEXT NOT NULL,
                        access_token_expires_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS received_telegram_messages (
                        telegram_update_id INTEGER PRIMARY KEY,
                        chat_id INTEGER NOT NULL,
                        message_thread_id INTEGER NOT NULL,
                        normalized_price TEXT,
                        normalized_address TEXT,
                        caption TEXT,
                        status TEXT NOT NULL CHECK (
                            status IN ('RECEIVED', 'PROCESSING', 'PUBLISHED', 'DUPLICATE', 'FAILED')
                        ),
                        duplicate_of_update_id INTEGER,
                        error TEXT,
                        received_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                statement.execute(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS uq_active_telegram_repost_key
                    ON received_telegram_messages(message_thread_id, normalized_price, normalized_address)
                    WHERE normalized_price IS NOT NULL
                      AND normalized_address IS NOT NULL
                      AND status IN ('PROCESSING', 'PUBLISHED')
                    """.trimIndent()
                )
                // Destination-specific uniqueness is enforced by repost_publications below.
                statement.execute("DROP INDEX IF EXISTS uq_active_telegram_repost_key")
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS published_reposts (
                        telegram_update_id INTEGER PRIMARY KEY,
                        message_thread_id INTEGER NOT NULL,
                        normalized_price TEXT,
                        normalized_address TEXT,
                        publish_id TEXT NOT NULL,
                        published_at INTEGER NOT NULL,
                        UNIQUE(message_thread_id, normalized_price, normalized_address)
                    )
                    """.trimIndent()
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS repost_publications (
                        telegram_update_id INTEGER NOT NULL,
                        destination TEXT NOT NULL CHECK (destination IN ('TIKTOK', 'THREADS')),
                        message_thread_id INTEGER NOT NULL,
                        normalized_price TEXT,
                        normalized_address TEXT,
                        status TEXT NOT NULL CHECK (status IN ('PROCESSING', 'PUBLISHED', 'DUPLICATE', 'FAILED')),
                        duplicate_of_update_id INTEGER,
                        publish_id TEXT,
                        error TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY (telegram_update_id, destination)
                    )
                    """.trimIndent()
                )
                statement.execute(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS uq_active_repost_destination_key
                    ON repost_publications(destination, message_thread_id, normalized_price, normalized_address)
                    WHERE normalized_price IS NOT NULL
                      AND normalized_address IS NOT NULL
                      AND status IN ('PROCESSING', 'PUBLISHED')
                    """.trimIndent()
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS tiktok_publish_attempts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        attempted_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                statement.execute(
                    "CREATE INDEX IF NOT EXISTS ix_tiktok_publish_attempts_time ON tiktok_publish_attempts(attempted_at)"
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS tiktok_publish_throttle (
                        id INTEGER PRIMARY KEY CHECK (id = 1),
                        blocked_until INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                val schemaVersion = statement.executeQuery("PRAGMA user_version").use { result ->
                    if (result.next()) result.getInt(1) else 0
                }
                if (schemaVersion < 6) {
                    if (schemaVersion < 1) {
                        // Remove the retired Bot API credential and overwrite its SQLite cell.
                        statement.executeUpdate("DELETE FROM app_secrets WHERE name = 'TELEGRAM_BOT_TOKEN'")
                    }
                    if (schemaVersion < 3) {
                        // OAuth application credentials are environment-only.
                        statement.executeUpdate(
                            """
                            DELETE FROM app_secrets WHERE name IN (
                                'TIKTOK_CLIENT_KEY', 'TIKTOK_CLIENT_SECRET',
                                'GOOGLE_CLIENT_ID', 'GOOGLE_CLIENT_SECRET',
                                'THREADS_APP_ID', 'THREADS_APP_SECRET'
                            )
                            """.trimIndent()
                        )
                    }
                    // Deployment identity is environment-only and must not become stale in SQLite.
                    statement.executeUpdate("DELETE FROM app_secrets WHERE name = 'TELEGRAM_USER_ID'")
                    statement.executeUpdate(
                        "DELETE FROM app_secrets WHERE name IN ('THREADS_APP_ID', 'THREADS_APP_SECRET')"
                    )
                    if (schemaVersion < 6) {
                        statement.executeUpdate(
                            """
                            INSERT OR IGNORE INTO repost_publications(
                                telegram_update_id, destination, message_thread_id,
                                normalized_price, normalized_address, status,
                                publish_id, created_at, updated_at
                            )
                            SELECT telegram_update_id, 'TIKTOK', message_thread_id,
                                   normalized_price, normalized_address, 'PUBLISHED',
                                   publish_id, published_at, published_at
                            FROM published_reposts
                            """.trimIndent()
                        )
                    }
                    statement.execute("PRAGMA user_version=6")
                    statement.execute("PRAGMA wal_checkpoint(TRUNCATE)")
                    statement.execute("VACUUM")
                }
            }
        }
        restrictFilePermissions()
    }

    fun connection(): Connection = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").also {
        it.createStatement().use { statement -> statement.execute("PRAGMA busy_timeout=5000") }
    }

    private fun restrictFilePermissions() {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }
}
