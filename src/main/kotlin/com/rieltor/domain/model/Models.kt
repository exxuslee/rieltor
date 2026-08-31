package com.rieltor.domain.model

import kotlinx.serialization.Serializable
import java.io.InputStream

@Serializable
data class StoredTokens(
    val openId: String,
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresAt: Long,
    val refreshTokenExpiresAt: Long,
)

data class StoredGoogleDriveTokens(
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresAt: Long,
)

data class StoredThreadsTokens(
    val userId: String,
    val accessToken: String,
    val accessTokenExpiresAt: Long,
)

enum class RepostDestination {
    TIKTOK,
    THREADS,
}

data class TelegramPhotoMessage(
    val updateId: Long,
    val chatId: Long,
    val messageThreadId: Long,
    val caption: String?,
    val photos: List<TelegramPhoto>,
)

data class TelegramRepostKey(
    val messageThreadId: Long,
    val price: String,
    val address: String,
)

data class ReceivedTelegramMessage(
    val updateId: Long,
    val chatId: Long,
    val messageThreadId: Long,
    val caption: String?,
    val repostKey: TelegramRepostKey?,
)

sealed interface TelegramMessageRegistration {
    data class Accepted(val repostKey: TelegramRepostKey?) : TelegramMessageRegistration
    data class Duplicate(val originalUpdateId: Long?) : TelegramMessageRegistration
}

/** Null messageThreadId means that every message in the chat is monitored. */
data class TelegramMonitoredTopic(
    val chatId: Long,
    val messageThreadId: Long? = null,
) {
    fun matches(chatId: Long, messageThreadId: Long): Boolean =
        this.chatId == chatId &&
            (this.messageThreadId == null || this.messageThreadId == messageThreadId)
}

data class TelegramPhoto(
    val fileName: String,
    val content: InputStream,
)

data class MediaTextOverlay(
    val title: String,
    val price: String?,
    val contact: String,
)

data class StoredMedia(
    val publicUrl: String,
    val localPath: String,
)

data class PublishReceipt(
    val publishId: String,
    val creatorName: String,
    val privacyLevel: String,
    val destination: RepostDestination = RepostDestination.TIKTOK,
)

data class RepostFailure(
    val destination: RepostDestination,
    val reason: String,
)

sealed interface RepostResult {
    data class Published(
        val receipts: List<PublishReceipt>,
        val failures: List<RepostFailure> = emptyList(),
    ) : RepostResult {
        val receipt: PublishReceipt get() = receipts.first()
    }
    data object IgnoredSource : RepostResult
    data object IgnoredContent : RepostResult
    data object Duplicate : RepostResult
}
