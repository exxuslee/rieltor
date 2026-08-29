package com.rieltor.domain.repository

import com.rieltor.domain.model.PublishReceipt

interface PhotoPublisher {
    suspend fun publish(photoUrls: List<String>, caption: String?): PublishReceipt
}