package com.rieltor.domain.repository

import com.rieltor.domain.model.TelegramMessageRegistration
import com.rieltor.domain.model.ReceivedTelegramMessage

    interface TelegramRepostRepository {
    /** Records every received message and atomically reserves a new listing identity for publication. */
    fun register(message: ReceivedTelegramMessage): TelegramMessageRegistration
    fun markRepostPublished(telegramUpdateId: Long, publishId: String)
    fun markRepostFailed(telegramUpdateId: Long, error: String)
}