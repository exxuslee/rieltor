package com.rieltor.application.worker

import com.rieltor.application.port.PublicationContext
import com.rieltor.application.port.Worker
import com.rieltor.application.service.CatalogRepostMasterLimiter
import com.rieltor.application.service.ListingCaptionFormatter
import com.rieltor.domain.model.*
import com.rieltor.domain.repository.PhotoPublisher
import com.rieltor.domain.repository.PublisherBackpressureException
import com.rieltor.infrastructure.config.JsonSettingsStore
import com.rieltor.infrastructure.database.model.AdEntity
import com.rieltor.infrastructure.database.repository.CatalogRepository
import com.rieltor.infrastructure.media.LocalPublicMediaStorage
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class RepostWorker(
    private val repository: CatalogRepository,
    private val settings: JsonSettingsStore,
    private val publishers: List<PhotoPublisher>,
    private val media: LocalPublicMediaStorage,
    private val now: () -> Long = System::currentTimeMillis,
) : Worker {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logger = LoggerFactory.getLogger(javaClass)
    private val limiter = CatalogRepostMasterLimiter(settings)
    private val started = AtomicBoolean()
    private val captionFormatter = ListingCaptionFormatter()

    override fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            while (isActive) {
                try {
                    runOnce()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    logger.error("Repost dispatch failed", error)
                }
                delay(1.minutes)
            }
        }
        scope.launch {
            while (isActive) {
                for (publisher in publishers.filter { it.destination == RepostDestination.TIKTOK }) {
                    try {
                        publisher.pendingDiagnostics()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        logger.warn("Pending reconciliation unavailable: {}", error.javaClass.simpleName)
                    }
                }
                delay(1.hours)
            }
        }
    }

    suspend fun runOnce() {
        val enabled = enabledPublishers()
        if (enabled.isEmpty() || limiter.waitUntilMillis(now()) > 0) return

        val attempt = prepareNextAttempt(enabled) ?: return
        for (publisher in attempt.publishers) {
            publishToDestination(attempt, publisher)
        }
    }

    /** One explicit send, independent of the automatic queue and its scheduling settings. */
    suspend fun repostTikTok(id: Long): PublishAttempt {
        require(id > 0) { "Expected a positive adsTab.id" }
        val row = checkNotNull(repository.listing(id)) { "adsTab.id=$id was not found" }
        check(row.status == ListingStatus.Active) { "adsTab.id=$id is not ACTIVE" }
        val publisher = publishers.single { it.destination == RepostDestination.TIKTOK }
        // Validate local inputs before creating a persistent attempt.
        photoUrls(row, publisher.maxPhotoCount)
        captionFormatter.forCatalog(row, settings.snapshot().repostContactPhone)
        val attemptId = repository.prepareManual(id, publisher.destination, now())
        publishToDestination(RepostAttempt(row, attemptId, listOf(publisher)), publisher)
        val state = repository.publication(checkNotNull(repository.repost(id)), publisher.destination)
        val result = state.attempts.single { it.attemptId == attemptId }
        check(result.status in setOf(RepostStatus.Published, RepostStatus.DeliveredDraft)) {
            "TikTok repost incomplete: adsTab.id=$id, status=${result.status.code}, " +
                "publishId=${result.publishId}, error=${result.error}"
        }
        return result
    }

    private fun enabledPublishers(): List<PhotoPublisher> {
        val config = settings.snapshot()
        return publishers.filter {
            when (it.destination) {
                RepostDestination.TIKTOK -> config.tiktokEnabled
                RepostDestination.THREADS -> config.threadsEnabled
            }
        }
    }

    private fun prepareNextAttempt(enabled: List<PhotoPublisher>): RepostAttempt? {
        val candidate = repository.nextRepost(
            enabled.any { it.destination == RepostDestination.TIKTOK },
            enabled.any { it.destination == RepostDestination.THREADS },
        ) ?: return null
        val row = candidate.listing
        val targets = enabled.filter { repository.status(candidate.repost, it.destination) == RepostStatus.Pending }
        val attemptId = repository.prepare(row.id, targets.map { it.destination }.toSet(), now()) ?: return null
        val attempt = RepostAttempt(row, attemptId, targets)
        if (limiter.reserve(attemptId, row.id, now()) > 0) {
            targets.forEach { publisher ->
                updateAttempt(attempt, publisher.destination) { it.copy(status = RepostStatus.Pending) }
            }
            return null
        }
        return attempt
    }

    private suspend fun publishToDestination(attempt: RepostAttempt, publisher: PhotoPublisher) {
        val row = attempt.listing
        val destination = publisher.destination
        try {
            val current = repository.listing(row.id) ?: return
            if (current.status != ListingStatus.Active || current.sourceRevision != row.sourceRevision) {
                updateAttempt(attempt, destination) { it.copy(status = RepostStatus.Pending) }
                return
            }
            val photoUrls = photoUrls(row, publisher.maxPhotoCount)
            val caption = captionFormatter.forCatalog(row, settings.snapshot().repostContactPhone)
            publisher.awaitPublishSlot()
            updateAttempt(attempt, destination) { it.copy(status = RepostStatus.Sending) }
            val receipt = withContext(PublicationContext(row.id, attempt.id)) {
                publisher.publish(photoUrls, caption)
            }
            updateAttempt(attempt, destination) {
                it.copy(
                    status = if (receipt.privacyLevel == "DRAFT" && it.status != RepostStatus.Published)
                        RepostStatus.DeliveredDraft else RepostStatus.Published,
                    publishId = receipt.publishId
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            handlePublishFailure(attempt, destination, error)
        }
    }

    private fun handlePublishFailure(attempt: RepostAttempt, destination: RepostDestination, error: Throwable) {
        updateAttempt(attempt, destination) {
            it.copy(
                status = when {
                    it.publishId != null -> it.status
                    error is PublisherBackpressureException -> RepostStatus.Pending
                    // Once sending starts, an external publication may exist even if the response was lost.
                    it.status == RepostStatus.Sending -> RepostStatus.Unknown
                    else -> RepostStatus.Failed
                },
                error = error.javaClass.simpleName
            )
        }
        if (error !is PublisherBackpressureException) {
            logger.warn(
                "Repost attempt incomplete. listing={}, destination={}, error={}",
                attempt.listing.id, destination, error.javaClass.simpleName
            )
        }
    }

    private fun updateAttempt(
        attempt: RepostAttempt,
        destination: RepostDestination,
        change: (PublishAttempt) -> PublishAttempt,
    ) = repository.changeAttempt(attempt.listing.id, destination, attempt.id, now(), change)

    private fun photoUrls(row: AdEntity, maxCount: Int): List<String> {
        val photos = Json.decodeFromString<List<CatalogPhoto>>(row.photos)
        check(photos.isNotEmpty() && photos.all { media.resolve(it.fileName) != null }) { "Listing media is unavailable" }
        return photos.take(maxCount).map { media.publicUrl(it.fileName) }
    }

    private data class RepostAttempt(val listing: AdEntity, val id: String, val publishers: List<PhotoPublisher>)

    override fun close() {
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
    }
}
