package com.rieltor.infrastructure.database

import com.rieltor.domain.model.ReceivedTelegramMessage
import com.rieltor.domain.model.RepostDestination
import com.rieltor.domain.model.TelegramMessageRegistration
import com.rieltor.domain.repository.TelegramRepostRepository
import java.sql.Connection
import java.time.Instant

class TelegramRepostRepositoryImpl(private val database: SqliteDatabase) : TelegramRepostRepository {
    override fun register(message: ReceivedTelegramMessage, destination: RepostDestination) =
        database.connection().use { connection ->
            connection.inTransaction {
                insertReceivedIfMissing(connection, message)
                findPublication(connection, message.updateId, destination)?.let { current ->
                    if (current.status != "FAILED") return@inTransaction TelegramMessageRegistration.Duplicate(
                        current.duplicateOfUpdateId ?: message.updateId
                    )
                }

                val original = message.repostKey?.let { findActive(connection, message, destination) }
                if (original != null) {
                    upsertPublication(connection, message, destination, "DUPLICATE", original)
                    markReceivedDuplicate(connection, message.updateId, original)
                    return@inTransaction TelegramMessageRegistration.Duplicate(original)
                }

                upsertPublication(connection, message, destination, "PROCESSING")
                markReceivedStatus(connection, message.updateId, "PROCESSING")
                TelegramMessageRegistration.Accepted(message.repostKey)
            }
        }

    override fun markRepostPublished(telegramUpdateId: Long, destination: RepostDestination, publishId: String) {
        val updateId = telegramUpdateId
        database.connection().use { connection ->
            connection.inTransaction {
                connection.prepareStatement(
                    """UPDATE repost_publications SET status='PUBLISHED', publish_id=?, error=NULL, updated_at=?
                       WHERE telegram_update_id=? AND destination=? AND status='PROCESSING'"""
                ).use { statement ->
                    statement.setString(1, publishId)
                    statement.setLong(2, now())
                    statement.setLong(3, updateId)
                    statement.setString(4, destination.name)
                    check(statement.executeUpdate() == 1) { "$destination publication for message $updateId was not reserved" }
                }
                markReceivedStatus(connection, updateId, "PUBLISHED")
            }
        }
    }

    override fun markRepostFailed(telegramUpdateId: Long, destination: RepostDestination, error: String) {
        val updateId = telegramUpdateId
        database.connection().use { connection ->
            connection.inTransaction {
                connection.prepareStatement(
                    """UPDATE repost_publications SET status='FAILED', error=?, updated_at=?
                       WHERE telegram_update_id=? AND destination=? AND status='PROCESSING'"""
                ).use { statement ->
                    statement.setString(1, error.take(1000))
                    statement.setLong(2, now())
                    statement.setLong(3, updateId)
                    statement.setString(4, destination.name)
                    statement.executeUpdate()
                }
                val hasActive = connection.prepareStatement(
                    "SELECT 1 FROM repost_publications WHERE telegram_update_id=? AND status IN ('PROCESSING','PUBLISHED') LIMIT 1"
                ).use { statement ->
                    statement.setLong(1, updateId)
                    statement.executeQuery().use { it.next() }
                }
                if (!hasActive) markReceivedStatus(connection, updateId, "FAILED")
            }
        }
    }

    private fun insertReceivedIfMissing(connection: Connection, message: ReceivedTelegramMessage) {
        connection.prepareStatement(
            """INSERT OR IGNORE INTO received_telegram_messages(
               telegram_update_id,chat_id,message_thread_id,normalized_price,normalized_address,caption,status,
               duplicate_of_update_id,error,received_at,updated_at)
               VALUES(?,?,?,?,?,?,'RECEIVED',NULL,NULL,?,?)"""
        ).use { statement ->
            val time = now()
            statement.setLong(1, message.updateId)
            statement.setLong(2, message.chatId)
            statement.setLong(3, message.messageThreadId)
            statement.setString(4, message.repostKey?.price)
            statement.setString(5, message.repostKey?.address)
            statement.setString(6, message.caption)
            statement.setLong(7, time)
            statement.setLong(8, time)
            statement.executeUpdate()
        }
    }

    private fun findPublication(connection: Connection, updateId: Long, destination: RepostDestination): Publication? =
        connection.prepareStatement(
            "SELECT status,duplicate_of_update_id FROM repost_publications WHERE telegram_update_id=? AND destination=?"
        ).use { statement ->
            statement.setLong(1, updateId)
            statement.setString(2, destination.name)
            statement.executeQuery().use { result ->
                if (!result.next()) null else Publication(
                    result.getString(1), result.getLong(2).takeUnless { result.wasNull() }
                )
            }
        }

    private fun findActive(connection: Connection, message: ReceivedTelegramMessage, destination: RepostDestination): Long? =
        connection.prepareStatement(
            """SELECT telegram_update_id FROM repost_publications
               WHERE destination=? AND message_thread_id=? AND normalized_price=? AND normalized_address=?
               AND telegram_update_id<>? AND status IN ('PROCESSING','PUBLISHED') LIMIT 1"""
        ).use { statement ->
            val key = requireNotNull(message.repostKey)
            statement.setString(1, destination.name)
            statement.setLong(2, key.messageThreadId)
            statement.setString(3, key.price)
            statement.setString(4, key.address)
            statement.setLong(5, message.updateId)
            statement.executeQuery().use { if (it.next()) it.getLong(1) else null }
        }

    private fun upsertPublication(
        connection: Connection,
        message: ReceivedTelegramMessage,
        destination: RepostDestination,
        status: String,
        duplicateOf: Long? = null,
    ) {
        connection.prepareStatement(
            """INSERT INTO repost_publications(
               telegram_update_id,destination,message_thread_id,normalized_price,normalized_address,status,
               duplicate_of_update_id,publish_id,error,created_at,updated_at)
               VALUES(?,?,?,?,?,?,?,NULL,NULL,?,?)
               ON CONFLICT(telegram_update_id,destination) DO UPDATE SET
               message_thread_id=excluded.message_thread_id,normalized_price=excluded.normalized_price,
               normalized_address=excluded.normalized_address,status=excluded.status,
               duplicate_of_update_id=excluded.duplicate_of_update_id,publish_id=NULL,error=NULL,updated_at=excluded.updated_at"""
        ).use { statement ->
            val time = now()
            statement.setLong(1, message.updateId)
            statement.setString(2, destination.name)
            statement.setLong(3, message.messageThreadId)
            statement.setString(4, message.repostKey?.price)
            statement.setString(5, message.repostKey?.address)
            statement.setString(6, status)
            if (duplicateOf == null) statement.setNull(7, java.sql.Types.BIGINT) else statement.setLong(7, duplicateOf)
            statement.setLong(8, time)
            statement.setLong(9, time)
            statement.executeUpdate()
        }
    }

    private fun markReceivedStatus(connection: Connection, updateId: Long, status: String) {
        connection.prepareStatement(
            "UPDATE received_telegram_messages SET status=?,duplicate_of_update_id=NULL,error=NULL,updated_at=? WHERE telegram_update_id=?"
        ).use { statement ->
            statement.setString(1, status)
            statement.setLong(2, now())
            statement.setLong(3, updateId)
            statement.executeUpdate()
        }
    }

    private fun markReceivedDuplicate(connection: Connection, updateId: Long, originalUpdateId: Long) {
        connection.prepareStatement(
            "UPDATE received_telegram_messages SET status='DUPLICATE',duplicate_of_update_id=?,updated_at=? WHERE telegram_update_id=?"
        ).use { statement ->
            statement.setLong(1, originalUpdateId)
            statement.setLong(2, now())
            statement.setLong(3, updateId)
            statement.executeUpdate()
        }
    }

    private fun <T> Connection.inTransaction(block: () -> T): T {
        createStatement().use { it.execute("BEGIN IMMEDIATE") }
        return try {
            block().also { createStatement().use { statement -> statement.execute("COMMIT") } }
        } catch (error: Throwable) {
            runCatching { createStatement().use { it.execute("ROLLBACK") } }
            throw error
        }
    }

    private fun now() = Instant.now().epochSecond
    private data class Publication(val status: String, val duplicateOfUpdateId: Long?)
}
