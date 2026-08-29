package com.rieltor.infrastructure.database

import com.rieltor.domain.model.StoredThreadsTokens
import com.rieltor.domain.repository.ThreadsTokenRepository
import java.time.Instant

class ThreadsTokenRepositoryImpl(
    private val database: SqliteDatabase,
) : ThreadsTokenRepository {
    override fun save(tokens: StoredThreadsTokens) {
        database.connection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO threads_tokens(user_id, access_token, access_token_expires_at, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(user_id) DO UPDATE SET
                    access_token = excluded.access_token,
                    access_token_expires_at = excluded.access_token_expires_at,
                    updated_at = excluded.updated_at
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, tokens.userId)
                statement.setString(2, tokens.accessToken)
                statement.setLong(3, tokens.accessTokenExpiresAt)
                statement.setLong(4, Instant.now().epochSecond)
                statement.executeUpdate()
            }
        }
    }

    override fun load(): StoredThreadsTokens? = database.connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT user_id, access_token, access_token_expires_at
            FROM threads_tokens ORDER BY updated_at DESC LIMIT 1
            """.trimIndent()
        ).use { statement ->
            statement.executeQuery().use { result ->
                if (!result.next()) null else StoredThreadsTokens(
                    userId = result.getString("user_id"),
                    accessToken = result.getString("access_token"),
                    accessTokenExpiresAt = result.getLong("access_token_expires_at"),
                )
            }
        }
    }
}
