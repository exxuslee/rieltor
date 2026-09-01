package com.rieltor.domain.repository

import com.rieltor.domain.model.TelegramPhoto

interface ExternalPhotoSource {
    suspend fun downloadPhotos(links: List<String>, limit: Int): List<TelegramPhoto>
}
