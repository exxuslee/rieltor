package com.rieltor.application.port

import com.rieltor.domain.model.TelegramBotIncomingMessage

/** Receives bot messages until closed; owns polling and message-processing jobs. */
interface TelegramBotMessageSource : AutoCloseable {
    fun start(onMessage: suspend (TelegramBotIncomingMessage) -> Unit)
    override fun close()
}
