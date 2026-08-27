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
                    CREATE TABLE IF NOT EXISTS post_jobs (
                        telegram_update_id INTEGER PRIMARY KEY,
                        status TEXT NOT NULL,
                        publish_id TEXT,
                        error TEXT,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                val schemaVersion = statement.executeQuery("PRAGMA user_version").use { result ->
                    if (result.next()) result.getInt(1) else 0
                }
                if (schemaVersion < 1) {
                    // Remove the retired Bot API credential and overwrite its SQLite cell.
                    statement.executeUpdate("DELETE FROM app_secrets WHERE name = 'TELEGRAM_BOT_TOKEN'")
                    statement.execute("PRAGMA user_version=1")
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
