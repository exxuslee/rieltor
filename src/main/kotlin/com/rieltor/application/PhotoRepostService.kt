package com.rieltor.application

import com.rieltor.domain.model.RepostResult
import com.rieltor.domain.model.TelegramMonitoredTopic
import com.rieltor.domain.model.TelegramPhoto
import com.rieltor.domain.model.TelegramPhotoMessage
import com.rieltor.domain.port.ExternalPhotoSource
import com.rieltor.domain.port.PhotoPublisher
import com.rieltor.domain.port.PostJobRepository
import com.rieltor.domain.port.PublicMediaStorage

class PhotoRepostService(
    private val jobs: PostJobRepository,
    private val mediaStorage: PublicMediaStorage,
    private val publisher: PhotoPublisher,
    private val externalPhotoSource: ExternalPhotoSource? = null,
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

        var extraPhotos = emptyList<TelegramPhoto>()
        return try {
            extraPhotos = externalPhotoSource?.downloadPhotos(
                message.caption,
                MAX_PHOTO_COUNT - message.photos.size,
            ).orEmpty()
            val allPhotos = message.photos + extraPhotos
            require(allPhotos.isNotEmpty()) { "At least one Telegram or Google Drive photo is required." }
            require(allPhotos.size <= MAX_PHOTO_COUNT) {
                "TikTok accepts at most $MAX_PHOTO_COUNT photos per post."
            }
            val media = allPhotos.map { photo ->
                photo.content.use { mediaStorage.store(photo.fileName, it) }
            }
            val receipt = publisher.publish(media.map { it.publicUrl }, messageFilter.filter(message.caption))
            jobs.markPublished(message.updateId, receipt.publishId)
            RepostResult.Published(receipt)
        } catch (error: Throwable) {
            jobs.markFailed(message.updateId, error.message ?: error.javaClass.simpleName)
            throw error
        } finally {
            message.closePhotos()
            extraPhotos.forEach { photo -> runCatching { photo.content.close() } }
        }
    }

    private fun TelegramPhotoMessage.closePhotos() {
        photos.forEach { photo -> runCatching { photo.content.close() } }
    }

    private companion object {
        const val MAX_PHOTO_COUNT = 35
    }
}
