package com.rieltor.application.worker

import com.rieltor.application.port.Worker
import com.rieltor.application.service.RepostCaptionFormatter
import com.rieltor.domain.model.*
import com.rieltor.domain.repository.PhotoPublisher
import com.rieltor.infrastructure.config.JsonSettingsStore
import com.rieltor.infrastructure.database.mapper.CatalogListingMapper
import com.rieltor.infrastructure.database.model.AdEntity
import com.rieltor.infrastructure.database.repository.CatalogRepository
import com.rieltor.infrastructure.media.LocalPublicMediaStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.time.delay
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

class RepostWorker(
    private val repository: CatalogRepository,
    private val settings: JsonSettingsStore,
    private val publishers: List<PhotoPublisher>,
    private val media: LocalPublicMediaStorage,
    private val now: () -> Long = System::currentTimeMillis,
) : Worker {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logger = LoggerFactory.getLogger(javaClass)
    private val dispatchMutexes = RepostDestination.entries.associateWith { Mutex() }
    private val started = AtomicBoolean()
    private val captionFormatter = RepostCaptionFormatter()
    private val listingMapper = CatalogListingMapper()
    private val NIGHT_START = LocalTime.of(23, 0)
    private val MORNING_END = LocalTime.of(7, 0)
    private val ZONE = ZoneId.of("Europe/Kyiv")

    override fun start() {
        if (!started.compareAndSet(false, true)) return
        for (publisher in publishers) {
            scope.launch {
                while (isActive) {
                    try {
                        runOnce(publisher)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        logger.error("Repost dispatch failed for {}", publisher.destination, error)
                    }
                    delay(nextDelay())
                }
            }
        }
    }

    suspend fun runOnce() {
        for (publisher in publishers) runOnce(publisher)
    }

    private suspend fun runOnce(publisher: PhotoPublisher) {
        dispatchMutexes.getValue(publisher.destination).withLock {
            if (!isEnabled(publisher.destination)) return
            if (settings.waitMillis(now(), publisher.destination) > 0) return
            val attempt = prepareNextAttempt(publisher) ?: return
            publishToDestination(attempt, publisher)
        }
    }

    /** One explicit send, independent of the automatic queue and its scheduling settings. */
    suspend fun repostTikTok(id: Long): RepostStatus = dispatchMutexes.getValue(RepostDestination.TIKTOK).withLock {
        require(id > 0) { "Expected a positive adsTab.id" }
        val row = checkNotNull(repository.listing(id)) { "adsTab.id=$id was not found" }
        check(row.status == ListingStatus.Active) { "adsTab.id=$id is not ACTIVE" }
        check(repository.isAllowRepost(id)) { "adsTab.id=$id forbids reposting (без реклами)" }
        check(row.chatId == REPOST_SOURCE_CHAT_ID) {
            "Reposts are allowed only from chatId=$REPOST_SOURCE_CHAT_ID"
        }

        val publisher = publishers.single { it.destination == RepostDestination.TIKTOK }
        // Validate local inputs before marking the listing as sending.
        photoUrls(row, publisher.maxPhotoCount)
        listingMapper.toMessage(row, settings.snapshot().repostContactPhone)

        check(repository.beginRepost(id, publisher.destination, manual = true)) { "Repost is unavailable or already sending" }
        val result = publishToDestination(RepostAttempt(row, java.util.UUID.randomUUID().toString()), publisher)
        check(result in setOf(RepostStatus.Published, RepostStatus.DeliveredDraft)) {
            "TikTok repost incomplete: adsTab.id=$id, status=${result.code}"
        }
        result
    }

    private fun isEnabled(destination: RepostDestination): Boolean {
        val config = settings.snapshot()
        return when (destination) {
            RepostDestination.TIKTOK -> config.tiktokEnabled
            RepostDestination.THREADS -> config.threadsEnabled
        }
    }

    private fun prepareNextAttempt(publisher: PhotoPublisher): RepostAttempt? {
        val row = repository.nextRepost(
            publisher.destination == RepostDestination.TIKTOK,
            publisher.destination == RepostDestination.THREADS,
        ) ?: return null
        val attemptId = java.util.UUID.randomUUID().toString()
        val attempt = RepostAttempt(row, attemptId)
        if (settings.reserve(attemptId, row.id, now(), publisher.destination) > 0) {
            return null
        }
        if (!repository.beginRepost(row.id, publisher.destination)) return null
        return attempt
    }

    private suspend fun publishToDestination(attempt: RepostAttempt, publisher: PhotoPublisher): RepostStatus {
        val row = attempt.listing
        val destination = publisher.destination
        var sending = false
        val result = try {
            val current = repository.listing(row.id)
            check(current != null && current.status == ListingStatus.Active && current.sourceRevision == row.sourceRevision) {
                "Advertisement changed before repost"
            }
            val photoUrls = photoUrls(row, publisher.maxPhotoCount)
            val listing = listingMapper.toMessage(row, settings.snapshot().repostContactPhone)
            publisher.awaitPublishSlot()
            check(repository.isAllowRepost(row.id)) { "Advertisement forbids reposting" }
            val selection = settings.reserveCaption(attempt.id, destination)
            val caption = captionFormatter.format(
                listing, selection.style, CatalogCodes.locationName(row.location),
                selection.conversationalVariant ?: ConversationalCaptionVariant.DIRECT,
                selection.balancedVariant ?: BalancedCaptionVariant.STRUCTURED,
            )
            sending = true
            val receipt = publisher.publish(photoUrls, caption)
            if (receipt.privacyLevel == "DRAFT") RepostStatus.DeliveredDraft else RepostStatus.Published
        } catch (error: CancellationException) {
            // SENDING remains persisted: restarting must not resend this listing.
            throw error
        } catch (error: Throwable) {
            logger.warn("Repost failed. listing={}, destination={}, error={}", row.id, destination, error.javaClass.simpleName)
            if (sending) RepostStatus.Unknown else RepostStatus.Failed
        }
        repository.finishRepost(row.id, destination, result, now())
        return result
    }

    private fun photoUrls(row: AdEntity, maxCount: Int): List<String> {
        val photos = Json.decodeFromString<List<CatalogPhoto>>(row.photos)
        check(photos.isNotEmpty() && photos.all { media.resolve(it.fileName) != null }) { "Listing media is unavailable" }
        return photos.take(maxCount).map { media.publicUrl(it.fileName) }
    }

    private data class RepostAttempt(val listing: AdEntity, val id: String)

    override fun close() {
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
    }

    private fun nextDelay(
        now: LocalDateTime = LocalDateTime.now(ZONE),
        regular: Duration = Duration.ofHours(2),
    ): Duration {
        val time = now.toLocalTime()
        val inNight = time !in MORNING_END..<NIGHT_START
        if (!inNight) return regular
        val wakeUpDate = if (time >= NIGHT_START) now.toLocalDate().plusDays(1) else now.toLocalDate()
        return Duration.between(now, wakeUpDate.atTime(MORNING_END))
    }
}
