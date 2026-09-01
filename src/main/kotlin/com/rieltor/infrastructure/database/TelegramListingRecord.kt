package com.rieltor.infrastructure.database

import com.rieltor.domain.model.TelegramListing
import com.rieltor.domain.model.TelegramRepostKey
import kotlinx.serialization.json.Json

/** SQLite-shaped data model for the persistable part of a Telegram listing. */
internal data class TelegramListingRecord(
    val updateId: Long,
    val chatId: Long,
    val messageThreadId: Long,
    val caption: String?,
    val googleDriveLinksJson: String,
    val normalizedPrice: String?,
    val normalizedAddress: String?,
) {
    val repostKey: TelegramRepostKey?
        get() = if (normalizedPrice != null && normalizedAddress != null) {
            TelegramRepostKey(messageThreadId, normalizedPrice, normalizedAddress)
        } else {
            null
        }

    companion object {
        fun from(listing: TelegramListing) = TelegramListingRecord(
            updateId = listing.updateId,
            chatId = listing.chatId,
            messageThreadId = listing.messageThreadId,
            caption = listing.caption,
            googleDriveLinksJson = Json.encodeToString(listing.googleDriveLinks),
            normalizedPrice = listing.repostKey?.price,
            normalizedAddress = listing.repostKey?.address,
        )
    }
}
