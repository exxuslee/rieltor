package com.rieltor.infrastructure.database

import com.rieltor.domain.model.StoredTokens
import com.rieltor.domain.port.PostJobRepository
import com.rieltor.domain.port.SecretRepository
import com.rieltor.domain.port.TikTokTokenRepository
import java.time.Instant

class SqliteRepositories(private val database: SqliteDatabase) :
    SecretRepository,
    TikTokTokenRepository,
    PostJobRepository {

    override fun get(name: String): String? = database.connection().use { connection ->
        connection.prepareStatement("SELECT value FROM app_secrets WHERE name = ?").use { statement ->
            statement.setString(1, name)
            statement.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
        }
    }

    override fun putIfAbsent(name: String, value: String) {
        if (value.isBlank()) return
        database.connection().use { connection ->
            connection.prepareStatement(
                "INSERT OR IGNORE INTO app_secrets(name, value, updated_at) VALUES (?, ?, ?)"
            ).use { statement ->
                statement.setString(1, name)
                statement.setString(2, value)
                statement.setLong(3, now())
                statement.executeUpdate()
            }
        }
    }

    override fun save(tokens: StoredTokens) {
        database.connection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO tiktok_tokens(
                    open_id, access_token, refresh_token,
                    access_token_expires_at, refresh_token_expires_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(open_id) DO UPDATE SET
                    access_token = excluded.access_token,
                    refresh_token = excluded.refresh_token,
                    access_token_expires_at = excluded.access_token_expires_at,
                    refresh_token_expires_at = excluded.refresh_token_expires_at,
                    updated_at = excluded.updated_at
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, tokens.openId)
                statement.setString(2, tokens.accessToken)
                statement.setString(3, tokens.refreshToken)
                statement.setLong(4, tokens.accessTokenExpiresAt)
                statement.setLong(5, tokens.refreshTokenExpiresAt)
                statement.setLong(6, now())
                statement.executeUpdate()
            }
        }
    }

    override fun find(openId: String): StoredTokens? = database.connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT open_id, access_token, refresh_token,
                   access_token_expires_at, refresh_token_expires_at
            FROM tiktok_tokens WHERE open_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, openId)
            statement.executeQuery().use { result -> if (result.next()) result.toTokens() else null }
        }
    }

    override fun latest(): StoredTokens? = database.connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT open_id, access_token, refresh_token,
                   access_token_expires_at, refresh_token_expires_at
            FROM tiktok_tokens ORDER BY updated_at DESC LIMIT 1
            """.trimIndent()
        ).use { statement ->
            statement.executeQuery().use { result -> if (result.next()) result.toTokens() else null }
        }
    }

    override fun tryStart(telegramUpdateId: Long): Boolean = database.connection().use { connection ->
        connection.prepareStatement(
            """
            INSERT INTO post_jobs(telegram_update_id, status, publish_id, error, updated_at)
            VALUES (?, 'PROCESSING', NULL, NULL, ?)
            ON CONFLICT(telegram_update_id) DO UPDATE SET
                status = 'PROCESSING',
                publish_id = NULL,
                error = NULL,
                updated_at = excluded.updated_at
            WHERE post_jobs.status = 'FAILED'
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, telegramUpdateId)
            statement.setLong(2, now())
            statement.executeUpdate() == 1
        }
    }

    override fun markPublished(telegramUpdateId: Long, publishId: String) {
        database.connection().use { updateJob(it, telegramUpdateId, "PUBLISHED", publishId, null) }
    }

    override fun markFailed(telegramUpdateId: Long, error: String) {
        database.connection().use { updateJob(it, telegramUpdateId, "FAILED", null, error.take(1000)) }
    }

    private fun updateJob(
        connection: java.sql.Connection,
        updateId: Long,
        status: String,
        publishId: String?,
        error: String?,
    ) {
        connection.prepareStatement(
            """
            UPDATE post_jobs
            SET status = ?, publish_id = ?, error = ?, updated_at = ?
            WHERE telegram_update_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, status)
            statement.setString(2, publishId)
            statement.setString(3, error)
            statement.setLong(4, now())
            statement.setLong(5, updateId)
            statement.executeUpdate()
        }
    }

    private fun java.sql.ResultSet.toTokens() = StoredTokens(
        openId = getString("open_id"),
        accessToken = getString("access_token"),
        refreshToken = getString("refresh_token"),
        accessTokenExpiresAt = getLong("access_token_expires_at"),
        refreshTokenExpiresAt = getLong("refresh_token_expires_at"),
    )

    private fun now(): Long = Instant.now().epochSecond
}
