package com.rieltor.application.port

import com.rieltor.application.model.TelegramSourceState
import com.rieltor.domain.model.TelegramListing
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Single-consumer message stream produced by a Telegram transport. */
interface TelegramMessageSource : AutoCloseable {
    val messages: Flow<TelegramListing>
    val state: StateFlow<TelegramSourceState>

    fun start()
}
