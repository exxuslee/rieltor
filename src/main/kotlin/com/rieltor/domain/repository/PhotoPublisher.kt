package com.rieltor.domain.repository

import com.rieltor.domain.model.PublishReceipt
import com.rieltor.domain.model.RepostDestination

interface PhotoPublisher {
    val destination: RepostDestination
    val maxPhotoCount: Int
    /** Waits until this destination is ready before expensive media preparation starts. */
    suspend fun awaitPublishSlot() = Unit
    suspend fun publish(photoUrls: List<String>, caption: String?): PublishReceipt
}
