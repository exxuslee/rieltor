package com.rieltor.domain.repository

import com.rieltor.domain.model.TelegramMessageRegistration
import com.rieltor.domain.model.ReceivedTelegramMessage
import com.rieltor.domain.model.RepostDestination

interface TelegramRepostRepository {
    /** Reserves a listing independently for one destination. */
    fun register(message: ReceivedTelegramMessage, destination: RepostDestination): TelegramMessageRegistration
    fun markRepostPublished(telegramUpdateId: Long, destination: RepostDestination, publishId: String)
    fun markRepostFailed(telegramUpdateId: Long, destination: RepostDestination, error: String)
}
