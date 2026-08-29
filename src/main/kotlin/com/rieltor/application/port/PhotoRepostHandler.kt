package com.rieltor.application.port

import com.rieltor.domain.model.RepostResult
import com.rieltor.domain.model.TelegramPhotoMessage

/** Handles one message and takes ownership of closing every photo stream. */
fun interface PhotoRepostHandler {
    suspend fun handle(message: TelegramPhotoMessage): RepostResult
}
