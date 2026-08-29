package com.rieltor.domain.usecase

import com.rieltor.domain.model.ReceivedTelegramMessage
import com.rieltor.domain.model.TelegramMessageRegistration
import com.rieltor.domain.model.TelegramPhotoMessage
import com.rieltor.domain.repository.TelegramRepostRepository

/** Owns the receive/reserve/publish lifecycle used to suppress repeated Telegram listings. */
class DeduplicateTelegramRepostUseCase(
    private val repository: TelegramRepostRepository,
    private val identityExtractor: TelegramListingIdentityExtractorUseCase = TelegramListingIdentityExtractorUseCase(),
) {
    fun reserve(message: TelegramPhotoMessage): TelegramMessageRegistration {
        val repostKey = identityExtractor.extract(message.messageThreadId, message.caption)
        return repository.register(
            ReceivedTelegramMessage(
                updateId = message.updateId,
                chatId = message.chatId,
                messageThreadId = message.messageThreadId,
                caption = message.caption,
                repostKey = repostKey,
            )
        )
    }

    fun markPublished(telegramUpdateId: Long, publishId: String) {
        repository.markRepostPublished(telegramUpdateId, publishId)
    }

    fun markFailed(telegramUpdateId: Long, error: Throwable) {
        repository.markRepostFailed(
            telegramUpdateId,
            error.message ?: error.javaClass.simpleName,
        )
    }
}
