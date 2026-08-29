package com.rieltor.application.service

import com.rieltor.domain.model.ReceivedTelegramMessage
import com.rieltor.domain.model.TelegramMessageRegistration
import com.rieltor.domain.model.TelegramPhotoMessage
import com.rieltor.domain.model.RepostDestination
import com.rieltor.domain.repository.TelegramRepostRepository
import com.rieltor.domain.service.TelegramListingIdentityExtractor

/** Owns the receive/reserve/publish lifecycle used to suppress repeated Telegram listings. */
class TelegramRepostTracker(
    private val repository: TelegramRepostRepository,
    private val identityExtractor: TelegramListingIdentityExtractor = TelegramListingIdentityExtractor(),
) {
    fun reserve(message: TelegramPhotoMessage, destination: RepostDestination): TelegramMessageRegistration {
        val repostKey = identityExtractor.extract(message.messageThreadId, message.caption)
        return repository.register(
            ReceivedTelegramMessage(
                updateId = message.updateId,
                chatId = message.chatId,
                messageThreadId = message.messageThreadId,
                caption = message.caption,
                repostKey = repostKey,
            ),
            destination,
        )
    }

    fun markPublished(telegramUpdateId: Long, destination: RepostDestination, publishId: String) {
        repository.markRepostPublished(telegramUpdateId, destination, publishId)
    }

    fun markFailed(telegramUpdateId: Long, destination: RepostDestination, error: Throwable) {
        repository.markRepostFailed(
            telegramUpdateId,
            destination,
            error.message ?: error.javaClass.simpleName,
        )
    }
}
