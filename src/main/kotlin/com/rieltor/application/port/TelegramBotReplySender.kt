package com.rieltor.application.port

import com.rieltor.domain.model.TelegramPhoto


interface TelegramBotReplySender {
    suspend fun sendText(
        chatId: Long,
        messageThreadId: Int?,
        replyToMessageId: Int?,
        text: String,
    )

    suspend fun sendPhotos(
        chatId: Long,
        messageThreadId: Int?,
        photos: List<TelegramPhoto>,
    )
}
