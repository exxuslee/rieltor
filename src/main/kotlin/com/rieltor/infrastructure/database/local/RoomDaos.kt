package com.rieltor.infrastructure.database.local

import androidx.room.*
import com.rieltor.infrastructure.database.model.ReceivedTelegramMessageEntity
import com.rieltor.infrastructure.database.model.TelegramListingRecord
import com.rieltor.infrastructure.database.model.TelegramRepostQueueEntity
import com.rieltor.infrastructure.database.model.TikTokPublishAttemptEntity

internal data class PublicationState(
    val status: String,
    @ColumnInfo(name = "duplicate_of_update_id") val duplicateOfUpdateId: Long?,
)

internal data class ReceivedMessageState(
    val status: String,
    @ColumnInfo(name = "duplicate_of_update_id") val duplicateOfUpdateId: Long?,
    @ColumnInfo(name = "google_drive_links") val googleDriveLinks: String,
)

@Dao
internal abstract class RepostDao {
    @Query("SELECT COUNT(*) FROM received_telegram_messages")
    abstract suspend fun receivedCount(): Int

    @Query(
        "SELECT status, duplicate_of_update_id, google_drive_links FROM received_telegram_messages " +
            "WHERE telegram_update_id = :updateId"
    )
    abstract suspend fun receivedState(updateId: Long): ReceivedMessageState?

    @Query(
        "SELECT publish_id FROM repost_publications " +
            "WHERE telegram_update_id = :updateId AND destination = :destination"
    )
    abstract suspend fun publishId(updateId: Long, destination: String): String?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertReceived(entity: ReceivedTelegramMessageEntity)

    @Query(
        "SELECT status, duplicate_of_update_id FROM repost_publications " +
            "WHERE telegram_update_id = :updateId AND destination = :destination"
    )
    protected abstract suspend fun findPublication(updateId: Long, destination: String): PublicationState?

    @Query(
        "SELECT telegram_update_id FROM repost_publications WHERE destination = :destination " +
            "AND message_thread_id = :messageThreadId AND normalized_price = :price " +
            "AND normalized_address = :address AND telegram_update_id <> :updateId " +
            "AND status IN ('PROCESSING', 'PUBLISHED') LIMIT 1"
    )
    protected abstract suspend fun findActive(
        destination: String,
        messageThreadId: Long,
        price: String,
        address: String,
        updateId: Long,
    ): Long?

    @Query(
        """INSERT INTO repost_publications(
            telegram_update_id, destination, message_thread_id, normalized_price, normalized_address,
            status, duplicate_of_update_id, publish_id, error, created_at, updated_at
        ) VALUES(:updateId, :destination, :messageThreadId, :price, :address, :status, :duplicateOf, NULL, NULL, :now, :now)
        ON CONFLICT(telegram_update_id, destination) DO UPDATE SET
            message_thread_id=excluded.message_thread_id, normalized_price=excluded.normalized_price,
            normalized_address=excluded.normalized_address, status=excluded.status,
            duplicate_of_update_id=excluded.duplicate_of_update_id, publish_id=NULL, error=NULL,
            updated_at=excluded.updated_at"""
    )
    protected abstract suspend fun upsertPublication(
        updateId: Long,
        destination: String,
        messageThreadId: Long,
        price: String?,
        address: String?,
        status: String,
        duplicateOf: Long?,
        now: Long,
    )

    @Query(
        "UPDATE received_telegram_messages SET status=:status, duplicate_of_update_id=NULL, " +
            "error=NULL, updated_at=:now WHERE telegram_update_id=:updateId"
    )
    protected abstract suspend fun markReceivedStatus(updateId: Long, status: String, now: Long)

    @Query(
        "UPDATE received_telegram_messages SET status='DUPLICATE', duplicate_of_update_id=:originalUpdateId, " +
            "updated_at=:now WHERE telegram_update_id=:updateId"
    )
    protected abstract suspend fun markReceivedDuplicate(updateId: Long, originalUpdateId: Long, now: Long)

    @Query(
        "UPDATE repost_publications SET status='PUBLISHED', publish_id=:publishId, error=NULL, updated_at=:now " +
            "WHERE telegram_update_id=:updateId AND destination=:destination AND status='PROCESSING'"
    )
    protected abstract suspend fun publish(updateId: Long, destination: String, publishId: String, now: Long): Int

    @Query(
        "UPDATE repost_publications SET status='FAILED', error=:error, updated_at=:now " +
            "WHERE telegram_update_id=:updateId AND destination=:destination AND status='PROCESSING'"
    )
    protected abstract suspend fun fail(updateId: Long, destination: String, error: String, now: Long)

    @Query(
        "SELECT EXISTS(SELECT 1 FROM repost_publications WHERE telegram_update_id=:updateId " +
            "AND status IN ('PROCESSING','PUBLISHED'))"
    )
    protected abstract suspend fun hasActive(updateId: Long): Boolean

    @Transaction
    open suspend fun register(
        message: TelegramListingRecord,
        destination: String,
        now: Long,
    ): RegistrationResult {
        insertReceived(message.toEntity(now))
        findPublication(message.updateId, destination)?.let { current ->
            if (current.status != "FAILED") {
                return RegistrationResult.Duplicate(current.duplicateOfUpdateId ?: message.updateId)
            }
        }

        val original = message.repostKey?.let { key ->
            findActive(destination, key.messageThreadId, key.price, key.address, message.updateId)
        }
        if (original != null) {
            upsertPublication(
                message.updateId, destination, message.messageThreadId,
                message.normalizedPrice, message.normalizedAddress, "DUPLICATE", original, now,
            )
            markReceivedDuplicate(message.updateId, original, now)
            return RegistrationResult.Duplicate(original)
        }

        upsertPublication(
            message.updateId, destination, message.messageThreadId,
            message.normalizedPrice, message.normalizedAddress, "PROCESSING", null, now,
        )
        markReceivedStatus(message.updateId, "PROCESSING", now)
        return RegistrationResult.Accepted
    }

    @Transaction
    open suspend fun markPublished(updateId: Long, destination: String, publishId: String, now: Long) {
        check(publish(updateId, destination, publishId, now) == 1) {
            "$destination publication for message $updateId was not reserved"
        }
        markReceivedStatus(updateId, "PUBLISHED", now)
    }

    @Transaction
    open suspend fun markFailed(updateId: Long, destination: String, error: String, now: Long) {
        fail(updateId, destination, error.take(1000), now)
        if (!hasActive(updateId)) markReceivedStatus(updateId, "FAILED", now)
    }
}

internal sealed interface RegistrationResult {
    data object Accepted : RegistrationResult
    data class Duplicate(val originalUpdateId: Long) : RegistrationResult
}

@Dao
internal abstract class RepostQueueDao {
    @Upsert
    protected abstract suspend fun upsertReceived(entity: ReceivedTelegramMessageEntity)

    @Upsert
    protected abstract suspend fun upsertQueue(entity: TelegramRepostQueueEntity)

    @Query("SELECT COUNT(*) FROM telegram_repost_queue WHERE claimed = 0")
    protected abstract suspend fun pendingCount(): Int

    @Query("SELECT * FROM telegram_repost_queue ORDER BY enqueued_at, telegram_update_id LIMIT 1")
    protected abstract suspend fun selectOldest(): TelegramRepostQueueEntity?

    @Query("SELECT * FROM telegram_repost_queue WHERE claimed = 0 ORDER BY enqueued_at, telegram_update_id LIMIT 1")
    protected abstract suspend fun oldestPending(): TelegramRepostQueueEntity?

    @Query("SELECT * FROM telegram_repost_queue ORDER BY enqueued_at, telegram_update_id")
    abstract suspend fun queueSnapshot(): List<TelegramRepostQueueEntity>

    @Query("UPDATE telegram_repost_queue SET claimed = 1 WHERE telegram_update_id = :updateId")
    protected abstract suspend fun claim(updateId: Long)

    @Query("UPDATE telegram_repost_queue SET claimed = 0")
    protected abstract suspend fun releaseAllClaims()

    @Query("DELETE FROM telegram_repost_queue WHERE telegram_update_id = :updateId")
    protected abstract suspend fun deleteQueue(updateId: Long)

    @Query(
        "UPDATE received_telegram_messages SET status=:status, error=:error, updated_at=:now " +
            "WHERE telegram_update_id=:updateId"
    )
    protected abstract suspend fun updateReceived(
        updateId: Long,
        status: String,
        error: String?,
        now: Long,
    )

    @Query(
        "UPDATE repost_publications SET status='FAILED', error='Interrupted by application restart', updated_at=:now " +
            "WHERE status='PROCESSING' AND telegram_update_id IN (SELECT telegram_update_id FROM telegram_repost_queue)"
    )
    protected abstract suspend fun failInterruptedPublications(now: Long)

    @Query(
        "UPDATE received_telegram_messages SET status='QUEUED', error=NULL, updated_at=:now " +
            "WHERE telegram_update_id IN (SELECT telegram_update_id FROM telegram_repost_queue)"
    )
    protected abstract suspend fun restoreQueuedStatuses(now: Long)

    @Query(
        "DELETE FROM repost_publications WHERE telegram_update_id IN (" +
            "SELECT telegram_update_id FROM received_telegram_messages WHERE received_at < :cutoff " +
            "AND telegram_update_id NOT IN (SELECT telegram_update_id FROM telegram_repost_queue))"
    )
    protected abstract suspend fun deleteOldPublications(cutoff: Long)

    @Query(
        "DELETE FROM published_reposts WHERE telegram_update_id IN (" +
            "SELECT telegram_update_id FROM received_telegram_messages WHERE received_at < :cutoff " +
            "AND telegram_update_id NOT IN (SELECT telegram_update_id FROM telegram_repost_queue))"
    )
    protected abstract suspend fun deleteOldLegacyPublications(cutoff: Long)

    @Query(
        "DELETE FROM received_telegram_messages WHERE received_at < :cutoff " +
            "AND telegram_update_id NOT IN (SELECT telegram_update_id FROM telegram_repost_queue)"
    )
    protected abstract suspend fun deleteOldReceived(cutoff: Long): Int

    @Transaction
    open suspend fun enqueue(
        message: ReceivedTelegramMessageEntity,
        queueItem: TelegramRepostQueueEntity,
        capacity: Int,
        now: Long,
    ): Long? {
        upsertReceived(message.copy(status = "QUEUED", updatedAt = now))
        upsertQueue(queueItem)
        if (pendingCount() <= capacity) return null
        val dropped = oldestPending()?.telegramUpdateId ?: return null
        deleteQueue(dropped)
        updateReceived(dropped, "DROPPED_QUEUE_OVERFLOW", null, now)
        return dropped
    }

    @Transaction
    open suspend fun reject(message: ReceivedTelegramMessageEntity, status: String, now: Long) {
        upsertReceived(message.copy(status = status, updatedAt = now))
        deleteQueue(message.telegramUpdateId)
    }

    @Transaction
    open suspend fun complete(updateId: Long, status: String, now: Long) {
        deleteQueue(updateId)
        updateReceived(updateId, status, null, now)
    }

    @Transaction
    open suspend fun markRetryPending(updateId: Long, error: String, now: Long) {
        updateReceived(updateId, "RETRY_PENDING", error.take(1000), now)
    }

    @Transaction
    open suspend fun recoverInterrupted(now: Long) {
        failInterruptedPublications(now)
        restoreQueuedStatuses(now)
        releaseAllClaims()
    }

    @Transaction
    open suspend fun claimOldest(): TelegramRepostQueueEntity? {
        val oldest = selectOldest() ?: return null
        if (!oldest.claimed) claim(oldest.telegramUpdateId)
        return oldest.copy(claimed = true)
    }

    @Transaction
    open suspend fun cleanHistoryBefore(cutoff: Long): Int {
        deleteOldPublications(cutoff)
        deleteOldLegacyPublications(cutoff)
        return deleteOldReceived(cutoff)
    }
}

@Dao
internal abstract class TikTokThrottleDao {
    @Query("DELETE FROM tiktok_publish_attempts WHERE attempted_at < :windowStart")
    protected abstract suspend fun deleteBefore(windowStart: Long)

    @Query("SELECT blocked_until FROM tiktok_publish_throttle WHERE id = 1")
    protected abstract suspend fun blockedUntil(): Long?

    @Query("SELECT attempted_at FROM tiktok_publish_attempts WHERE attempted_at >= :windowStart ORDER BY attempted_at")
    protected abstract suspend fun attemptsSince(windowStart: Long): List<Long>

    @Insert
    protected abstract suspend fun insertAttempt(entity: TikTokPublishAttemptEntity)

    @Query(
        """INSERT INTO tiktok_publish_throttle(id, blocked_until) VALUES(1, :blockedUntil)
        ON CONFLICT(id) DO UPDATE SET blocked_until = MAX(blocked_until, excluded.blocked_until)"""
    )
    abstract suspend fun blockUntil(blockedUntil: Long)

    @Transaction
    open suspend fun reserveSlot(
        nowMillis: Long,
        windowMillis: Long,
        maxPostsPerWindow: Int,
        minIntervalMillis: Long,
    ): Long {
        val windowStart = nowMillis - windowMillis
        deleteBefore(windowStart)
        val attempts = attemptsSince(windowStart)
        val intervalReadyAt = attempts.lastOrNull()?.plus(minIntervalMillis) ?: nowMillis
        val windowReadyAt = if (attempts.size >= maxPostsPerWindow) {
            attempts[attempts.size - maxPostsPerWindow] + windowMillis
        } else {
            nowMillis
        }
        val readyAt = maxOf(nowMillis, blockedUntil() ?: 0L, intervalReadyAt, windowReadyAt)
        return if (readyAt <= nowMillis) {
            insertAttempt(TikTokPublishAttemptEntity(attemptedAt = nowMillis))
            0L
        } else {
            readyAt - nowMillis
        }
    }
}

private fun TelegramListingRecord.toEntity(now: Long) = ReceivedTelegramMessageEntity(
    telegramUpdateId = updateId,
    chatId = chatId,
    messageThreadId = messageThreadId,
    normalizedPrice = normalizedPrice,
    normalizedAddress = normalizedAddress,
    caption = caption,
    googleDriveLinks = googleDriveLinksJson,
    status = "RECEIVED",
    duplicateOfUpdateId = null,
    error = null,
    receivedAt = now,
    updatedAt = now,
)
