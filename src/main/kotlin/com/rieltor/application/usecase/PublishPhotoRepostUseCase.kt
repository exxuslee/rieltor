package com.rieltor.application.usecase

import com.rieltor.application.port.PhotoRepostHandler
import com.rieltor.application.service.TelegramRepostTracker
import com.rieltor.domain.model.RepostResult
import com.rieltor.domain.model.TelegramMessageRegistration
import com.rieltor.domain.model.TelegramMonitoredTopic
import com.rieltor.domain.model.TelegramPhoto
import com.rieltor.domain.model.TelegramPhotoMessage
import com.rieltor.domain.repository.ExternalPhotoSource
import com.rieltor.domain.repository.PhotoPublisher
import com.rieltor.domain.repository.PublicMediaStorage
import com.rieltor.domain.service.TikTokCaptionFormatter

class PublishPhotoRepostUseCase(
    private val repostTracker: TelegramRepostTracker,
    private val mediaStorage: PublicMediaStorage,
    private val publisher: PhotoPublisher,
    private val externalPhotoSource: ExternalPhotoSource? = null,
    private val allowedSources: Set<TelegramMonitoredTopic> = emptySet(),
    private val captionFormatter: TikTokCaptionFormatter = TikTokCaptionFormatter(),
) : PhotoRepostHandler {
    override suspend fun handle(message: TelegramPhotoMessage): RepostResult {
        if (allowedSources.none { source -> source.matches(message.chatId, message.messageThreadId) }) {
            message.closePhotos()
            return RepostResult.IgnoredSource
        }
        if (message.photos.isEmpty() && externalPhotoSource?.containsLink(message.caption) != true) {
            message.closePhotos()
            return RepostResult.IgnoredContent
        }
        val registration = try {
            repostTracker.reserve(message)
        } catch (error: Throwable) {
            message.closePhotos()
            throw error
        }
        if (registration is TelegramMessageRegistration.Duplicate) {
            message.closePhotos()
            return RepostResult.Duplicate
        }

        var extraPhotos = emptyList<TelegramPhoto>()
        var submittedToTikTok = false
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
            val receipt = publisher.publish(media.map { it.publicUrl }, captionFormatter.filter(message.caption))
            submittedToTikTok = true
            repostTracker.markPublished(message.updateId, receipt.publishId)
            RepostResult.Published(receipt)
        } catch (error: Throwable) {
            if (!submittedToTikTok) {
                runCatching { repostTracker.markFailed(message.updateId, error) }
                    .onFailure(error::addSuppressed)
            }
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
