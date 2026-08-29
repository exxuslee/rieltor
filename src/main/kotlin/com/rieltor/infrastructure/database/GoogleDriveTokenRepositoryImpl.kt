package com.rieltor.infrastructure.database

import com.rieltor.domain.model.StoredGoogleDriveTokens
import com.rieltor.domain.repository.GoogleDriveTokenRepository
import java.time.Instant

class GoogleDriveTokenRepositoryImpl(
    private val database: SqliteDatabase,
) : GoogleDriveTokenRepository {
    override fun save(tokens: StoredGoogleDriveTokens) {
        database.connection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO google_drive_tokens(
                    id, access_token, refresh_token, access_token_expires_at, updated_at
                ) VALUES (1, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    access_token = excluded.access_token,
                    refresh_token = excluded.refresh_token,
                    access_token_expires_at = excluded.access_token_expires_at,
                    updated_at = excluded.updated_at
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, tokens.accessToken)
                statement.setString(2, tokens.refreshToken)
                statement.setLong(3, tokens.accessTokenExpiresAt)
                statement.setLong(4, Instant.now().epochSecond)
                statement.executeUpdate()
            }
        }
    }

    override fun load(): StoredGoogleDriveTokens? = database.connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT access_token, refresh_token, access_token_expires_at
            FROM google_drive_tokens WHERE id = 1
            """.trimIndent()
        ).use { statement ->
            statement.executeQuery().use { result ->
                if (!result.next()) return@use null
                StoredGoogleDriveTokens(
                    accessToken = result.getString("access_token"),
                    refreshToken = result.getString("refresh_token"),
                    accessTokenExpiresAt = result.getLong("access_token_expires_at"),
                )
            }
        }
    }
}
