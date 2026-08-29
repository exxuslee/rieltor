package com.rieltor.domain.repository

import com.rieltor.domain.model.PublishReceipt
import com.rieltor.domain.model.RepostDestination

interface PhotoPublisher {
    val destination: RepostDestination
    val maxPhotoCount: Int
    suspend fun publish(photoUrls: List<String>, caption: String?): PublishReceipt
}
