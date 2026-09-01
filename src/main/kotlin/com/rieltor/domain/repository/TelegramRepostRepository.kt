package com.rieltor.domain.repository

import com.rieltor.domain.model.RepostDestination
import com.rieltor.domain.model.TelegramListing
import com.rieltor.domain.model.TelegramMessageRegistration

interface TelegramRepostRepository {
    /** Reserves a listing independently for one destination. */
    fun register(listing: TelegramListing, destination: RepostDestination): TelegramMessageRegistration
    fun markRepostPublished(telegramUpdateId: Long, destination: RepostDestination, publishId: String)
    fun markRepostFailed(telegramUpdateId: Long, destination: RepostDestination, error: String)
}
