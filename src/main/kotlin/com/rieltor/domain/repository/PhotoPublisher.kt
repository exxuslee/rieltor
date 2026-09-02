package com.rieltor.domain.repository

import com.rieltor.domain.model.PublishReceipt
import com.rieltor.domain.model.RepostDestination

interface PhotoPublisher {
    val destination: RepostDestination
    val maxPhotoCount: Int
    /** Waits until this destination is ready before expensive media preparation starts. */
    suspend fun awaitPublishSlot() = Unit
    suspend fun pendingDiagnostics(): PublisherPendingDiagnostics? = null
    suspend fun publish(photoUrls: List<String>, caption: String?): PublishReceipt
}

/** A temporary destination capacity condition that should defer, rather than fail, the FIFO head. */
open class PublisherBackpressureException(message: String) : Exception(message)

data class PublisherPendingDiagnostics(
    val destination: RepostDestination,
    val trackedCount: Int,
    val pendingCount: Int,
    val statuses: List<String>,
)
