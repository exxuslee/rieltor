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

@Serializable
data class StoredGoogleDriveTokens(
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresAt: Long,
)

@Serializable
data class StoredThreadsTokens(
    val userId: String,
    val accessToken: String,
    val accessTokenExpiresAt: Long,
)

enum class RepostDestination {
    TIKTOK,
    THREADS,
}

/**
 * A property listing received from Telegram and used throughout the repost flow.
 *
 * Photo streams are runtime-only. The repository maps the remaining fields to its
 * persistence model instead of introducing another application/domain entity.
 */
data class TelegramListing(
    val updateId: Long,
    val chatId: Long,
    val messageThreadId: Long,
    val caption: String?,
    val photos: List<TelegramPhoto>,
    val googleDriveLinks: List<String> = emptyList(),
    val repostKey: TelegramRepostKey? = null,
    val normalizedPrice: String? = repostKey?.price,
)

data class TelegramRepostKey(
    val messageThreadId: Long,
    val price: String,
    val address: String,
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
    val localPath: String? = null,
)

data class MediaTextOverlay(
    val title: String,
    val price: String?,
    val contact: String,
)

/**
 * Public, destination-independent representation of a property listing.
 *
 * Source contacts and internal commercial notes must be removed before this model is built.
 */
data class ListingMessage(
    val title: String,
    val price: String,
    val address: String?,
    val keyParameters: List<String>,
    val additionalParameters: List<String>,
    val governmentPrograms: String?,
    val registration: String?,
    val hashtags: List<String>,
    val phone: String,
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
