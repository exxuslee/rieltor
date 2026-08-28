package com.rieltor.application

import com.rieltor.domain.model.RepostResult
import com.rieltor.domain.model.TelegramMonitoredTopic
import com.rieltor.domain.model.TelegramPhotoMessage
import com.rieltor.domain.port.PhotoPublisher
import com.rieltor.domain.port.PostJobRepository
import com.rieltor.domain.port.PublicMediaStorage

class PhotoRepostService(
    private val jobs: PostJobRepository,
    private val mediaStorage: PublicMediaStorage,
    private val publisher: PhotoPublisher,
    private val allowedSources: Set<TelegramMonitoredTopic> = emptySet(),
    private val messageFilter: TikTokMessageFilter = TikTokMessageFilter(),
) {
    suspend fun handle(message: TelegramPhotoMessage): RepostResult {
        if (allowedSources.none { source -> source.matches(message.chatId, message.messageThreadId) }) {
            message.closePhotos()
            return RepostResult.IgnoredSource
        }
        if (!jobs.tryStart(message.updateId)) {
            message.closePhotos()
            return RepostResult.Duplicate
        }

        return try {
            val media = try {
                message.photos.map { photo ->
                    photo.content.use { mediaStorage.store(photo.fileName, it) }
                }
            } finally {
                message.closePhotos()
            }
            val receipt = publisher.publish(media.map { it.publicUrl }, messageFilter.filter(message.caption))
            jobs.markPublished(message.updateId, receipt.publishId)
            RepostResult.Published(receipt)
        } catch (error: Throwable) {
            jobs.markFailed(message.updateId, error.message ?: error.javaClass.simpleName)
            throw error
        }
    }

    private fun TelegramPhotoMessage.closePhotos() {
        photos.forEach { photo -> runCatching { photo.content.close() } }
    }
}
