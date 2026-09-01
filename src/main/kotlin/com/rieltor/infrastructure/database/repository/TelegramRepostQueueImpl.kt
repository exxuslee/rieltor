package com.rieltor.infrastructure.database.repository

import com.rieltor.domain.model.TelegramListing
import com.rieltor.domain.model.TelegramPhoto
import com.rieltor.domain.model.TelegramRepostKey
import com.rieltor.domain.repository.QueueEnqueueResult
import com.rieltor.domain.repository.TelegramRepostQueue
import com.rieltor.domain.repository.TelegramRepostQueueSnapshot
import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.model.ReceivedTelegramMessageEntity
import com.rieltor.infrastructure.database.model.TelegramListingRecord
import com.rieltor.infrastructure.database.model.TelegramRepostQueueEntity
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class TelegramRepostQueueImpl(
    private val database: RoomDatabaseStore,
) : TelegramRepostQueue {
    override fun recoverInterrupted() = database.blocking {
        it.repostQueueDao().recoverInterrupted(Instant.now().epochSecond)
    }

    override fun enqueue(listing: TelegramListing, capacity: Int): QueueEnqueueResult {
        require(capacity > 0) { "Repost queue capacity must be positive." }
        val now = Instant.now().epochSecond
        val record = TelegramListingRecord.from(listing)
        val photoPaths = listing.photos.mapNotNull { photo ->
            photo.localPath?.let(Path::of)?.toAbsolutePath()?.normalize()?.toString()
        }
        return try {
            val dropped = database.blocking {
                it.repostQueueDao().enqueue(
                    record.toReceived(now, "QUEUED"),
                    TelegramRepostQueueEntity(
                        telegramUpdateId = record.updateId,
                        chatId = record.chatId,
                        messageThreadId = record.messageThreadId,
                        caption = record.caption,
                        googleDriveLinks = record.googleDriveLinksJson,
                        normalizedPrice = record.normalizedPrice,
                        normalizedAddress = record.normalizedAddress,
                        telegramPhotoPaths = Json.encodeToString(photoPaths),
                        enqueuedAt = now,
                        claimed = false,
                    ),
                    capacity,
                    now,
                )
            }
            QueueEnqueueResult(dropped)
        } finally {
            listing.photos.forEach { photo -> runCatching { photo.content.close() } }
        }
    }

    override fun peekOldest(): TelegramListing? = database.blocking { room ->
        room.repostQueueDao().claimOldest()?.toListing()
    }

    override fun snapshot(): TelegramRepostQueueSnapshot = database.blocking { room ->
        val entries = room.repostQueueDao().queueSnapshot()
        TelegramRepostQueueSnapshot(
            claimedUpdateId = entries.firstOrNull { it.claimed }?.telegramUpdateId,
            pendingUpdateIds = entries.filterNot { it.claimed }.map { it.telegramUpdateId },
        )
    }

    override fun complete(updateId: Long, status: String) = database.blocking {
        it.repostQueueDao().complete(updateId, status, Instant.now().epochSecond)
    }

    override fun reject(listing: TelegramListing, status: String) {
        val now = Instant.now().epochSecond
        val record = TelegramListingRecord.from(listing)
        try {
            database.blocking { it.repostQueueDao().reject(record.toReceived(now, status), status, now) }
        } finally {
            listing.photos.forEach { photo -> runCatching { photo.content.close() } }
        }
    }

    override fun markRetryPending(updateId: Long, reason: String) = database.blocking {
        it.repostQueueDao().markRetryPending(updateId, reason, Instant.now().epochSecond)
    }

    override fun cleanHistoryBefore(cutoffEpochSeconds: Long): Int = database.blocking {
        it.repostQueueDao().cleanHistoryBefore(cutoffEpochSeconds)
    }

    private fun TelegramRepostQueueEntity.toListing(): TelegramListing {
        val paths = runCatching { Json.decodeFromString<List<String>>(telegramPhotoPaths) }.getOrDefault(emptyList())
        val photos = paths.mapNotNull { value ->
            val path = runCatching { Path.of(value) }.getOrNull() ?: return@mapNotNull null
            if (!Files.isRegularFile(path)) return@mapNotNull null
            TelegramPhoto(path.fileName.toString(), Files.newInputStream(path), path.toString())
        }
        val key = if (normalizedPrice != null && normalizedAddress != null) {
            TelegramRepostKey(messageThreadId, normalizedPrice, normalizedAddress)
        } else {
            null
        }
        return TelegramListing(
            updateId = telegramUpdateId,
            chatId = chatId,
            messageThreadId = messageThreadId,
            caption = caption,
            photos = photos,
            googleDriveLinks = runCatching {
                Json.decodeFromString<List<String>>(googleDriveLinks)
            }.getOrDefault(emptyList()),
            repostKey = key,
            normalizedPrice = normalizedPrice,
        )
    }
}

private fun TelegramListingRecord.toReceived(now: Long, status: String) = ReceivedTelegramMessageEntity(
    telegramUpdateId = updateId,
    chatId = chatId,
    messageThreadId = messageThreadId,
    normalizedPrice = normalizedPrice,
    normalizedAddress = normalizedAddress,
    caption = caption,
    googleDriveLinks = googleDriveLinksJson,
    status = status,
    duplicateOfUpdateId = null,
    error = null,
    receivedAt = now,
    updatedAt = now,
)
