package com.rieltor.infrastructure.database

import com.rieltor.domain.model.ReceivedTelegramMessage
import com.rieltor.domain.model.TelegramMessageRegistration
import com.rieltor.domain.repository.TelegramRepostRepository
import java.sql.Connection
import java.time.Instant

class TelegramRepostRepositoryImpl(
    private val database: SqliteDatabase,
) : TelegramRepostRepository {
    override fun register(message: ReceivedTelegramMessage): TelegramMessageRegistration =
        database.connection().use { connection ->
            connection.inTransaction {
                val existing = findReceivedMessage(connection, message.updateId)
                if (existing != null && existing.status != "FAILED") {
                    return@inTransaction TelegramMessageRegistration.Duplicate(
                        existing.duplicateOfUpdateId ?: message.updateId
                    )
                }

                if (existing == null) insertReceivedMessage(connection, message)
                else resetFailedMessage(connection, message)

                val key = message.repostKey
                if (key == null) {
                    markReceivedStatus(connection, message.updateId, "PROCESSING")
                    return@inTransaction TelegramMessageRegistration.Accepted(null)
                }

                val originalUpdateId = findPublishedRepost(connection, message)
                    ?: findActiveReceivedMessage(connection, message)
                if (originalUpdateId != null) {
                    markReceivedStatus(
                        connection = connection,
                        telegramUpdateId = message.updateId,
                        status = "DUPLICATE",
                        duplicateOfUpdateId = originalUpdateId,
                    )
                    return@inTransaction TelegramMessageRegistration.Duplicate(originalUpdateId)
                }

                markReceivedStatus(connection, message.updateId, "PROCESSING")
                TelegramMessageRegistration.Accepted(key)
            }
        }

    override fun markRepostPublished(telegramUpdateId: Long, publishId: String) {
        database.connection().use { connection ->
            connection.inTransaction {
                val key = loadStoredRepostKey(connection, telegramUpdateId)
                    ?: error("Telegram message $telegramUpdateId was not registered before publication")
                connection.prepareStatement(
                    """
                    INSERT INTO published_reposts(
                        telegram_update_id, message_thread_id, normalized_price,
                        normalized_address, publish_id, published_at
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(telegram_update_id) DO UPDATE SET
                        publish_id = excluded.publish_id,
                        published_at = excluded.published_at
                    """.trimIndent()
                ).use { statement ->
                    statement.setLong(1, telegramUpdateId)
                    statement.setLong(2, key.messageThreadId)
                    statement.setString(3, key.price)
                    statement.setString(4, key.address)
                    statement.setString(5, publishId)
                    statement.setLong(6, now())
                    statement.executeUpdate()
                }
                markReceivedStatus(connection, telegramUpdateId, "PUBLISHED")
            }
        }
    }

    override fun markRepostFailed(telegramUpdateId: Long, error: String) {
        database.connection().use { connection ->
            connection.prepareStatement(
                """
                UPDATE received_telegram_messages
                SET status = 'FAILED', error = ?, updated_at = ?
                WHERE telegram_update_id = ? AND status = 'PROCESSING'
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, error.take(1000))
                statement.setLong(2, now())
                statement.setLong(3, telegramUpdateId)
                statement.executeUpdate()
            }
        }
    }

    private fun findReceivedMessage(connection: Connection, updateId: Long): ReceivedStatus? =
        connection.prepareStatement(
            """
            SELECT status, duplicate_of_update_id
            FROM received_telegram_messages WHERE telegram_update_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, updateId)
            statement.executeQuery().use { result ->
                if (!result.next()) return@use null
                ReceivedStatus(
                    status = result.getString("status"),
                    duplicateOfUpdateId = result.getLong("duplicate_of_update_id")
                        .takeUnless { result.wasNull() },
                )
            }
        }

    private fun insertReceivedMessage(connection: Connection, message: ReceivedTelegramMessage) {
        connection.prepareStatement(
            """
            INSERT INTO received_telegram_messages(
                telegram_update_id, chat_id, message_thread_id, normalized_price,
                normalized_address, caption, status, duplicate_of_update_id,
                error, received_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, 'RECEIVED', NULL, NULL, ?, ?)
            """.trimIndent()
        ).use { statement ->
            val timestamp = now()
            statement.setLong(1, message.updateId)
            statement.setLong(2, message.chatId)
            statement.setLong(3, message.messageThreadId)
            statement.setString(4, message.repostKey?.price)
            statement.setString(5, message.repostKey?.address)
            statement.setString(6, message.caption)
            statement.setLong(7, timestamp)
            statement.setLong(8, timestamp)
            statement.executeUpdate()
        }
    }

    private fun resetFailedMessage(connection: Connection, message: ReceivedTelegramMessage) {
        connection.prepareStatement(
            """
            UPDATE received_telegram_messages
            SET chat_id = ?, message_thread_id = ?, normalized_price = ?, normalized_address = ?,
                caption = ?, status = 'RECEIVED', duplicate_of_update_id = NULL,
                error = NULL, updated_at = ?
            WHERE telegram_update_id = ? AND status = 'FAILED'
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, message.chatId)
            statement.setLong(2, message.messageThreadId)
            statement.setString(3, message.repostKey?.price)
            statement.setString(4, message.repostKey?.address)
            statement.setString(5, message.caption)
            statement.setLong(6, now())
            statement.setLong(7, message.updateId)
            check(statement.executeUpdate() == 1) { "Could not retry Telegram message ${message.updateId}" }
        }
    }

    private fun findPublishedRepost(connection: Connection, message: ReceivedTelegramMessage): Long? =
        findMatchingUpdateId(
            connection,
            """
            SELECT telegram_update_id FROM published_reposts
            WHERE message_thread_id = ? AND normalized_price = ? AND normalized_address = ?
            LIMIT 1
            """.trimIndent(),
            message,
        )

    private fun findActiveReceivedMessage(connection: Connection, message: ReceivedTelegramMessage): Long? =
        findMatchingUpdateId(
            connection,
            """
            SELECT telegram_update_id FROM received_telegram_messages
            WHERE message_thread_id = ? AND normalized_price = ? AND normalized_address = ?
              AND telegram_update_id <> ? AND status IN ('PROCESSING', 'PUBLISHED')
            LIMIT 1
            """.trimIndent(),
            message,
            includeCurrentUpdateId = true,
        )

    private fun findMatchingUpdateId(
        connection: Connection,
        sql: String,
        message: ReceivedTelegramMessage,
        includeCurrentUpdateId: Boolean = false,
    ): Long? = connection.prepareStatement(sql).use { statement ->
        val key = requireNotNull(message.repostKey)
        statement.setLong(1, key.messageThreadId)
        statement.setString(2, key.price)
        statement.setString(3, key.address)
        if (includeCurrentUpdateId) statement.setLong(4, message.updateId)
        statement.executeQuery().use { result -> if (result.next()) result.getLong(1) else null }
    }

    private fun loadStoredRepostKey(connection: Connection, updateId: Long): StoredRepostKey? =
        connection.prepareStatement(
            """
            SELECT message_thread_id, normalized_price, normalized_address
            FROM received_telegram_messages WHERE telegram_update_id = ? AND status = 'PROCESSING'
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, updateId)
            statement.executeQuery().use { result ->
                if (!result.next()) return@use null
                StoredRepostKey(
                    messageThreadId = result.getLong("message_thread_id"),
                    price = result.getString("normalized_price"),
                    address = result.getString("normalized_address"),
                )
            }
        }

    private fun markReceivedStatus(
        connection: Connection,
        telegramUpdateId: Long,
        status: String,
        duplicateOfUpdateId: Long? = null,
    ) {
        connection.prepareStatement(
            """
            UPDATE received_telegram_messages
            SET status = ?, duplicate_of_update_id = ?, error = NULL, updated_at = ?
            WHERE telegram_update_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, status)
            if (duplicateOfUpdateId == null) statement.setNull(2, java.sql.Types.BIGINT)
            else statement.setLong(2, duplicateOfUpdateId)
            statement.setLong(3, now())
            statement.setLong(4, telegramUpdateId)
            check(statement.executeUpdate() == 1) { "Unknown Telegram message $telegramUpdateId" }
        }
    }

    private fun <T> Connection.inTransaction(block: () -> T): T {
        check(autoCommit) { "Nested SQLite transactions are not supported" }
        createStatement().use { it.execute("BEGIN IMMEDIATE") }
        return try {
            val result = block()
            createStatement().use { it.execute("COMMIT") }
            result
        } catch (error: Throwable) {
            runCatching { createStatement().use { it.execute("ROLLBACK") } }
            throw error
        }
    }

    private fun now(): Long = Instant.now().epochSecond

    private data class ReceivedStatus(
        val status: String,
        val duplicateOfUpdateId: Long?,
    )

    private data class StoredRepostKey(
        val messageThreadId: Long,
        val price: String?,
        val address: String?,
    )
}
