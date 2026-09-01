package com.rieltor.application.usecase

import com.rieltor.application.port.PhotoRepostHandler
import com.rieltor.application.service.TelegramRepostTracker
import com.rieltor.domain.model.*
import com.rieltor.domain.repository.ExternalPhotoSource
import com.rieltor.domain.repository.PhotoPublisher
import com.rieltor.domain.repository.PublicMediaStorage
import com.rieltor.domain.service.ListingCaptionFormatter
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

class PublishPhotoRepostUseCase(
    private val repostTracker: TelegramRepostTracker,
    private val mediaStorage: PublicMediaStorage,
    private val publishers: List<PhotoPublisher>,
    private val externalPhotoSource: ExternalPhotoSource? = null,
    private val allowedSources: Set<TelegramMonitoredTopic> = emptySet(),
    private val captionFormatter: ListingCaptionFormatter = ListingCaptionFormatter(),
    private val maxPhotoCount: Int = Int.MAX_VALUE,
) : PhotoRepostHandler {
    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun handle(listing: TelegramListing): RepostResult {
        if (allowedSources.none { source -> source.matches(listing.chatId, listing.messageThreadId) }) {
            listing.closePhotos()
            return RepostResult.IgnoredSource
        }
        if (listing.photos.isEmpty() && listing.googleDriveLinks.isEmpty()) {
            listing.closePhotos()
            return RepostResult.IgnoredContent
        }
        val registrations = publishers.associateWith { publisher ->
            runCatching { repostTracker.reserve(listing, publisher.destination) }
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
            listing.closePhotos()
            if (reservationFailures.isNotEmpty()) throw RepostPublishException(reservationFailures)
            return RepostResult.Duplicate
        }

        var extraPhotos = emptyList<TelegramPhoto>()
        return try {
            require(maxPhotoCount > 0) { "Repost photo limit must be positive." }
            val effectivePhotoLimit = minOf(activePublishers.maxOf { it.maxPhotoCount }, maxPhotoCount)
            extraPhotos = loadExtraPhotos(listing, effectivePhotoLimit)
            val allPhotos = (listing.photos + extraPhotos).take(effectivePhotoLimit)
            require(allPhotos.isNotEmpty()) { "At least one Telegram or Google Drive photo is required." }
            val publicListing = captionFormatter.filter(listing.caption)
            val caption = captionFormatter.forTikTok(publicListing)
            val textOverlay = captionFormatter.photoOverlay(publicListing)
            val media = allPhotos.mapIndexed { index, photo ->
                photo.content.use {
//                    mediaStorage.store(photo.fileName, it, textOverlay.takeIf { index == 0 })
                    mediaStorage.store(photo.fileName, it, null)
                }
            }
            // The production VM has one OCPU and 1 GB RAM. Running TikTok and Threads
            // requests concurrently only increases peak memory/connection pressure there.
            // Keep destinations failure-isolated, but publish them one at a time.
            val outcomes = mutableListOf<Pair<RepostDestination, Result<PublishReceipt>>>()
            val orderedPublishers = activePublishers.toList()
            for ((index, publisher) in orderedPublishers.withIndex()) {
                val outcome = runCatching {
                    val receipt = publisher.publish(
                        media.map { it.publicUrl }.take(minOf(publisher.maxPhotoCount, maxPhotoCount)),
                        caption,
                    )
                    repostTracker.markPublished(listing.updateId, publisher.destination, receipt.publishId)
                    receipt
                }.onFailure { error ->
                    runCatching {
                        repostTracker.markFailed(listing.updateId, publisher.destination, error)
                    }.onFailure(error::addSuppressed)
                    orderedPublishers.drop(index + 1).forEach { waitingPublisher ->
                        runCatching {
                            repostTracker.markFailed(
                                listing.updateId,
                                waitingPublisher.destination,
                                IllegalStateException("Previous destination failed; sequential batch will be retried"),
                            )
                        }.onFailure(error::addSuppressed)
                    }
                }
                outcomes += publisher.destination to outcome
                if (outcome.isFailure) break
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
                runCatching { repostTracker.markFailed(listing.updateId, publisher.destination, error) }
                    .onFailure(error::addSuppressed)
            }
            throw error
        } finally {
            listing.closePhotos()
            extraPhotos.forEach { photo -> runCatching { photo.content.close() } }
        }
    }

    private fun TelegramListing.closePhotos() {
        photos.forEach { photo -> runCatching { photo.content.close() } }
    }

    private suspend fun loadExtraPhotos(listing: TelegramListing, photoLimit: Int): List<TelegramPhoto> {
        val source = externalPhotoSource ?: return emptyList()
        val remainingPhotoCount = (photoLimit - listing.photos.size).coerceAtLeast(0)
        if (remainingPhotoCount == 0 || listing.googleDriveLinks.isEmpty()) return emptyList()

        return try {
            source.downloadPhotos(listing.googleDriveLinks, remainingPhotoCount)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (listing.photos.isEmpty()) throw error
            logger.warn(
                "Could not add Google Drive photos; continuing with Telegram media. updateId={}, reason={}",
                listing.updateId,
                error.message ?: error.javaClass.simpleName,
            )
            emptyList()
        }
    }

}

class RepostPublishException(val failures: List<RepostFailure>) : Exception(
    failures.joinToString("; ") { "${it.destination}: ${it.reason}" }
)
