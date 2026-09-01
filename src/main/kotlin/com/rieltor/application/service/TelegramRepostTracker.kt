package com.rieltor.application.service

import com.rieltor.domain.model.RepostDestination
import com.rieltor.domain.model.TelegramListing
import com.rieltor.domain.model.TelegramMessageRegistration
import com.rieltor.domain.repository.TelegramRepostRepository

/** Owns the receive/reserve/publish lifecycle used to suppress repeated Telegram listings. */
class TelegramRepostTracker(
    private val repository: TelegramRepostRepository,
) {
    fun reserve(listing: TelegramListing, destination: RepostDestination): TelegramMessageRegistration =
        repository.register(listing, destination)

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
