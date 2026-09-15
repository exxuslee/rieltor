package com.rieltor.application.port

import com.rieltor.domain.model.SourceRefresh

interface TelegramInboxSource : AutoCloseable {
    fun start()
    suspend fun refresh(chatId: Long, messageId: Long): SourceRefresh
}
