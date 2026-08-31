package com.rieltor.application.usecase

import com.rieltor.application.port.PhotoRepostHandler
import com.rieltor.application.service.TelegramRepostTracker
import com.rieltor.domain.model.RepostResult
import com.rieltor.domain.model.RepostFailure
import com.rieltor.domain.model.TelegramMessageRegistration
import com.rieltor.domain.model.TelegramMonitoredTopic
import com.rieltor.domain.model.TelegramPhoto
import com.rieltor.domain.model.TelegramPhotoMessage
import com.rieltor.domain.repository.ExternalPhotoSource
import com.rieltor.domain.repository.PhotoPublisher
import com.rieltor.domain.repository.PublicMediaStorage
import com.rieltor.domain.service.ListingCaptionFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import org.slf4j.LoggerFactory

class PublishPhotoRepostUseCase(
    private val repostTracker: TelegramRepostTracker,
    private val mediaStorage: PublicMediaStorage,
    private val publishers: List<PhotoPublisher>,
    private val externalPhotoSource: ExternalPhotoSource? = null,
    private val allowedSources: Set<TelegramMonitoredTopic> = emptySet(),
    private val captionFormatter: ListingCaptionFormatter = ListingCaptionFormatter(),
) : PhotoRepostHandler {
    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun handle(message: TelegramPhotoMessage): RepostResult {
        if (allowedSources.none { source -> source.matches(message.chatId, message.messageThreadId) }) {
            message.closePhotos()
            return RepostResult.IgnoredSource
        }
        if (message.photos.isEmpty() && externalPhotoSource?.containsLink(message.caption) != true) {
            message.closePhotos()
            return RepostResult.IgnoredContent
        }
        val registrations = publishers.associateWith { publisher ->
            runCatching { repostTracker.reserve(message, publisher.destination) }
        }
        val reservationFailures = registrations.mapNotNull { (publisher, result) ->
            result.exceptionOrNull()?.let { error ->
                RepostFailure(publisher.destination, error.message ?: error.javaClass.simpleName)
            }
        }
        val activePublishers = registrations
            .filterValues { result -> result.getOrNull() is TelegramMessageRegistration.Accepted }
            .keys
        if (activePublishers.isEmpty()) {
            message.closePhotos()
            if (reservationFailures.isNotEmpty()) throw RepostPublishException(reservationFailures)
            return RepostResult.Duplicate
        }

        var extraPhotos = emptyList<TelegramPhoto>()
        return try {
            extraPhotos = loadExtraPhotos(message, activePublishers.maxOf { it.maxPhotoCount })
            val allPhotos = message.photos + extraPhotos
            require(allPhotos.isNotEmpty()) { "At least one Telegram or Google Drive photo is required." }
            val media = allPhotos.map { photo ->
                photo.content.use { mediaStorage.store(photo.fileName, it) }
            }
            val caption = captionFormatter.filter(message.caption)
            val outcomes = supervisorScope {
                activePublishers.map { publisher ->
                    async {
                        runCatching {
                            val receipt = publisher.publish(
                                media.map { it.publicUrl }.take(publisher.maxPhotoCount),
                                caption,
                            )
                            repostTracker.markPublished(message.updateId, publisher.destination, receipt.publishId)
                            receipt
                        }.onFailure { error ->
                            runCatching {
                                repostTracker.markFailed(message.updateId, publisher.destination, error)
                            }.onFailure(error::addSuppressed)
                        }.let { publisher.destination to it }
                    }
                }.awaitAll()
            }
            val receipts = outcomes.mapNotNull { it.second.getOrNull() }
            val failures = reservationFailures + outcomes.mapNotNull { (destination, result) ->
                result.exceptionOrNull()?.let { error ->
                    RepostFailure(destination, error.message ?: error.javaClass.simpleName)
                }
            }
            if (receipts.isEmpty()) throw RepostPublishException(failures)
            RepostResult.Published(receipts, failures)
        } catch (error: Throwable) {
            activePublishers.forEach { publisher ->
                runCatching { repostTracker.markFailed(message.updateId, publisher.destination, error) }
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

    private suspend fun loadExtraPhotos(message: TelegramPhotoMessage, photoLimit: Int): List<TelegramPhoto> {
        val source = externalPhotoSource ?: return emptyList()
        val remainingPhotoCount = (photoLimit - message.photos.size).coerceAtLeast(0)
        if (remainingPhotoCount == 0 || !source.containsLink(message.caption)) return emptyList()

        return try {
            source.downloadPhotos(message.caption, remainingPhotoCount)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (message.photos.isEmpty()) throw error
            logger.warn(
                "Could not add Google Drive photos; continuing with Telegram media. updateId={}, reason={}",
                message.updateId,
                error.message ?: error.javaClass.simpleName,
            )
            emptyList()
        }
    }

}

class RepostPublishException(val failures: List<RepostFailure>) : Exception(
    failures.joinToString("; ") { "${it.destination}: ${it.reason}" }
)
