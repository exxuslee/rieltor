package com.rieltor.domain.repository

import com.rieltor.domain.model.TelegramPhoto

interface ExternalPhotoSource {
    fun containsLink(text: String?): Boolean
    suspend fun downloadPhotos(text: String?, limit: Int): List<TelegramPhoto>
}