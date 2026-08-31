package com.rieltor.infrastructure.database

import com.rieltor.domain.repository.TikTokPublishThrottleRepository
import java.sql.Connection

class TikTokPublishThrottleRepositoryImpl(
    private val database: SqliteDatabase,
) : TikTokPublishThrottleRepository {
    override fun reserveSlot(
        nowMillis: Long,
        windowMillis: Long,
        maxPostsPerWindow: Int,
        minIntervalMillis: Long,
    ): Long = database.connection().use { connection ->
        connection.inTransaction {
            val windowStart = nowMillis - windowMillis
            connection.prepareStatement("DELETE FROM tiktok_publish_attempts WHERE attempted_at < ?").use {
                it.setLong(1, windowStart)
                it.executeUpdate()
            }
            val blockedUntil = connection.prepareStatement(
                "SELECT blocked_until FROM tiktok_publish_throttle WHERE id = 1"
            ).use { statement ->
                statement.executeQuery().use { result -> if (result.next()) result.getLong(1) else 0L }
            }
            val attempts = connection.prepareStatement(
                "SELECT attempted_at FROM tiktok_publish_attempts WHERE attempted_at >= ? ORDER BY attempted_at"
            ).use { statement ->
                statement.setLong(1, windowStart)
                statement.executeQuery().use { result ->
                    buildList { while (result.next()) add(result.getLong(1)) }
                }
            }
            val intervalReadyAt = attempts.lastOrNull()?.plus(minIntervalMillis) ?: nowMillis
            val windowReadyAt = if (attempts.size >= maxPostsPerWindow) {
                attempts[attempts.size - maxPostsPerWindow] + windowMillis
            } else nowMillis
            val readyAt = maxOf(nowMillis, blockedUntil, intervalReadyAt, windowReadyAt)
            if (readyAt <= nowMillis) {
                connection.prepareStatement("INSERT INTO tiktok_publish_attempts(attempted_at) VALUES(?)").use {
                    it.setLong(1, nowMillis)
                    it.executeUpdate()
                }
                0L
            } else readyAt - nowMillis
        }
    }

    override fun blockUntil(blockedUntilMillis: Long) {
        database.connection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO tiktok_publish_throttle(id, blocked_until) VALUES(1, ?)
                ON CONFLICT(id) DO UPDATE SET blocked_until = MAX(blocked_until, excluded.blocked_until)
                """.trimIndent()
            ).use {
                it.setLong(1, blockedUntilMillis)
                it.executeUpdate()
            }
        }
    }

    private fun <T> Connection.inTransaction(block: () -> T): T {
        createStatement().use { it.execute("BEGIN IMMEDIATE") }
        return try {
            block().also { createStatement().use { statement -> statement.execute("COMMIT") } }
        } catch (error: Throwable) {
            runCatching { createStatement().use { statement -> statement.execute("ROLLBACK") } }
            throw error
        }
    }
}
