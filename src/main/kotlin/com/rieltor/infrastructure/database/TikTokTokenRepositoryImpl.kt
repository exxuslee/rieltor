package com.rieltor.infrastructure.database

import com.rieltor.domain.model.StoredTokens
import com.rieltor.domain.repository.TikTokTokenRepository
import java.time.Instant

class TikTokTokenRepositoryImpl(
    private val database: SqliteDatabase,
) : TikTokTokenRepository {
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
                statement.setLong(6, Instant.now().epochSecond)
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

    private fun java.sql.ResultSet.toTokens() = StoredTokens(
        openId = getString("open_id"),
        accessToken = getString("access_token"),
        refreshToken = getString("refresh_token"),
        accessTokenExpiresAt = getLong("access_token_expires_at"),
        refreshTokenExpiresAt = getLong("refresh_token_expires_at"),
    )
}
