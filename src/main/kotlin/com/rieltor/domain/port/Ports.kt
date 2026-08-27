package com.rieltor.domain.port

import com.rieltor.domain.model.PublishReceipt
import com.rieltor.domain.model.StoredMedia
import com.rieltor.domain.model.StoredTokens
import java.io.InputStream

interface SecretRepository {
    fun get(name: String): String?
    fun put(name: String, value: String)
    fun putIfAbsent(name: String, value: String)
}

interface TikTokTokenRepository {
    fun save(tokens: StoredTokens)
    fun find(openId: String): StoredTokens?
    fun latest(): StoredTokens?
}

interface PostJobRepository {
    fun tryStart(telegramUpdateId: Long): Boolean
    fun markPublished(telegramUpdateId: Long, publishId: String)
    fun markFailed(telegramUpdateId: Long, error: String)
}

interface PublicMediaStorage {
    fun store(fileName: String, content: InputStream): StoredMedia
}

interface PhotoPublisher {
    suspend fun publish(photoUrl: String, caption: String?): PublishReceipt
}
