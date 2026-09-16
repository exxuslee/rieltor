package com.rieltor.domain.model

data class TelegramBotIncomingMessage(
    val chatId: Long,
    val messageId: Int,
    val messageThreadId: Int?,
    val text: String,
)