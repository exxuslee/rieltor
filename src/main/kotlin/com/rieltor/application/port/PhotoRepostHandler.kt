package com.rieltor.application.port

import com.rieltor.domain.model.RepostResult
import com.rieltor.domain.model.TelegramListing
import com.rieltor.domain.repository.PublisherPendingDiagnostics

/** Handles one message and takes ownership of closing every photo stream. */
fun interface PhotoRepostHandler {
    suspend fun handle(listing: TelegramListing): RepostResult

    suspend fun pendingDiagnostics(): List<PublisherPendingDiagnostics> = emptyList()
}
