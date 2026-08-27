package com.rieltor.application

import com.rieltor.domain.model.RepostResult
import com.rieltor.domain.model.TelegramPhotoMessage
import com.rieltor.domain.port.PhotoPublisher
import com.rieltor.domain.port.PostJobRepository
import com.rieltor.domain.port.PublicMediaStorage

class PhotoRepostService(
    private val allowedSenderId: Long,
    private val jobs: PostJobRepository,
    private val mediaStorage: PublicMediaStorage,
    private val publisher: PhotoPublisher,
) {
    suspend fun handle(message: TelegramPhotoMessage): RepostResult {
        if (message.senderId != allowedSenderId) {
            message.content.close()
            return RepostResult.IgnoredSender
        }
        if (!jobs.tryStart(message.updateId)) {
            message.content.close()
            return RepostResult.Duplicate
        }

        return try {
            val media = message.content.use { mediaStorage.store(message.fileName, it) }
            val receipt = publisher.publish(media.publicUrl, message.caption)
            jobs.markPublished(message.updateId, receipt.publishId)
            RepostResult.Published(receipt)
        } catch (error: Throwable) {
            jobs.markFailed(message.updateId, error.message ?: error.javaClass.simpleName)
            throw error
        }
    }
}
