package com.rieltor.application.port

import com.rieltor.domain.model.SourcePhoto
import com.rieltor.domain.model.SourceRefresh

interface TelegramInboxSource : AutoCloseable {
    fun start()
    suspend fun refresh(chatId: Long, messageId: Long): SourceRefresh

    /**
     * Original bytes of a photo attached to the Telegram post, or null when the session
     * cannot serve it right now. Callers must retry instead of publishing an incomplete album.
     */
    suspend fun downloadPhoto(photo: SourcePhoto): ByteArray? = null
}
