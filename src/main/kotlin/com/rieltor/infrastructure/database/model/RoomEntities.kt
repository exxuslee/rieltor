package com.rieltor.infrastructure.database.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "received_telegram_messages")
internal data class ReceivedTelegramMessageEntity(
    @PrimaryKey @ColumnInfo(name = "telegram_update_id") val telegramUpdateId: Long,
    @ColumnInfo(name = "chat_id") val chatId: Long,
    @ColumnInfo(name = "message_thread_id") val messageThreadId: Long,
    @ColumnInfo(name = "normalized_price") val normalizedPrice: String?,
    @ColumnInfo(name = "normalized_address") val normalizedAddress: String?,
    val caption: String?,
    @ColumnInfo(name = "google_drive_links", defaultValue = "'[]'") val googleDriveLinks: String,
    val status: String,
    @ColumnInfo(name = "duplicate_of_update_id") val duplicateOfUpdateId: Long?,
    val error: String?,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "published_reposts")
internal data class PublishedRepostEntity(
    @PrimaryKey @ColumnInfo(name = "telegram_update_id") val telegramUpdateId: Long,
    @ColumnInfo(name = "message_thread_id") val messageThreadId: Long,
    @ColumnInfo(name = "normalized_price") val normalizedPrice: String?,
    @ColumnInfo(name = "normalized_address") val normalizedAddress: String?,
    @ColumnInfo(name = "publish_id") val publishId: String,
    @ColumnInfo(name = "published_at") val publishedAt: Long,
)

@Entity(
    tableName = "repost_publications",
    primaryKeys = ["telegram_update_id", "destination"],
)
internal data class RepostPublicationEntity(
    @ColumnInfo(name = "telegram_update_id") val telegramUpdateId: Long,
    val destination: String,
    @ColumnInfo(name = "message_thread_id") val messageThreadId: Long,
    @ColumnInfo(name = "normalized_price") val normalizedPrice: String?,
    @ColumnInfo(name = "normalized_address") val normalizedAddress: String?,
    val status: String,
    @ColumnInfo(name = "duplicate_of_update_id") val duplicateOfUpdateId: Long?,
    @ColumnInfo(name = "publish_id") val publishId: String?,
    val error: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "telegram_repost_queue",
    indices = [Index(value = ["enqueued_at"], name = "ix_telegram_repost_queue_fifo")],
)
internal data class TelegramRepostQueueEntity(
    @PrimaryKey @ColumnInfo(name = "telegram_update_id") val telegramUpdateId: Long,
    @ColumnInfo(name = "chat_id") val chatId: Long,
    @ColumnInfo(name = "message_thread_id") val messageThreadId: Long,
    val caption: String?,
    @ColumnInfo(name = "google_drive_links") val googleDriveLinks: String,
    @ColumnInfo(name = "normalized_price") val normalizedPrice: String?,
    @ColumnInfo(name = "normalized_address") val normalizedAddress: String?,
    @ColumnInfo(name = "telegram_photo_paths") val telegramPhotoPaths: String,
    @ColumnInfo(name = "enqueued_at") val enqueuedAt: Long,
    @ColumnInfo(defaultValue = "0") val claimed: Boolean = false,
)

@Entity(
    tableName = "tiktok_publish_attempts",
    indices = [Index(value = ["attempted_at"], name = "ix_tiktok_publish_attempts_time")],
)
internal data class TikTokPublishAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "attempted_at") val attemptedAt: Long,
)

@Entity(tableName = "tiktok_publish_throttle")
internal data class TikTokPublishThrottleEntity(
    @PrimaryKey val id: Long = 1,
    @ColumnInfo(name = "blocked_until", defaultValue = "0") val blockedUntil: Long,
)

@Entity(
    tableName = "tiktok_tracked_publishes",
    indices = [Index(value = ["created_at"], name = "ix_tiktok_tracked_publishes_created_at")],
)
internal data class TikTokTrackedPublishEntity(
    @PrimaryKey @ColumnInfo(name = "publish_id") val publishId: String,
    val mode: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_status") val lastStatus: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
