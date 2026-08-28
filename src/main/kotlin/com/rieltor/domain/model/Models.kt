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

data class TelegramPhotoMessage(
    val updateId: Long,
    val chatId: Long,
    val messageThreadId: Long,
    val caption: String?,
    val photos: List<TelegramPhoto>,
)

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

data class StoredMedia(
    val publicUrl: String,
    val localPath: String,
)

data class PublishReceipt(
    val publishId: String,
    val creatorName: String,
    val privacyLevel: String,
)

sealed interface RepostResult {
    data class Published(val receipt: PublishReceipt) : RepostResult
    data object IgnoredSource : RepostResult
    data object Duplicate : RepostResult
}
