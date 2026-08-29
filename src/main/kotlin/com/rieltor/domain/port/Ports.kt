package com.rieltor.domain.port

import com.rieltor.domain.model.PublishReceipt
import com.rieltor.domain.model.StoredGoogleDriveTokens
import com.rieltor.domain.model.StoredMedia
import com.rieltor.domain.model.StoredTokens
import com.rieltor.domain.model.TelegramPhoto
import java.io.InputStream

interface SecretRepository {
    fun get(name: String): String?
    fun putIfAbsent(name: String, value: String)
}

interface TikTokTokenRepository {
    fun save(tokens: StoredTokens)
    fun find(openId: String): StoredTokens?
    fun latest(): StoredTokens?
}

interface GoogleDriveTokenRepository {
    fun save(tokens: StoredGoogleDriveTokens)
    fun load(): StoredGoogleDriveTokens?
}

interface PostJobRepository {
    fun tryStart(telegramUpdateId: Long): Boolean
    fun markPublished(telegramUpdateId: Long, publishId: String)
    fun markFailed(telegramUpdateId: Long, error: String)
}

interface PublicMediaStorage {
    fun store(fileName: String, content: InputStream): StoredMedia
}

interface ExternalPhotoSource {
    fun containsLink(text: String?): Boolean
    suspend fun downloadPhotos(text: String?, limit: Int): List<TelegramPhoto>
}

interface PhotoPublisher {
    suspend fun publish(photoUrls: List<String>, caption: String?): PublishReceipt
}
