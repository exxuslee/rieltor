package com.rieltor.application.worker

import com.rieltor.application.port.PublicationContext
import com.rieltor.application.service.CatalogRepostMasterLimiter
import com.rieltor.domain.model.CatalogPhoto
import com.rieltor.domain.model.ListingMessage
import com.rieltor.domain.model.RepostDestination
import com.rieltor.domain.repository.PhotoPublisher
import com.rieltor.domain.repository.PublisherBackpressureException
import com.rieltor.domain.service.ListingCaptionFormatter
import com.rieltor.infrastructure.config.JsonSettingsStore
import com.rieltor.infrastructure.database.model.ListingEntity
import com.rieltor.infrastructure.database.repository.CatalogRepository
import com.rieltor.infrastructure.media.LocalPublicMediaStorage
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

class CatalogRepostWorker(
    private val repository: CatalogRepository,
    private val settings: JsonSettingsStore,
    private val publishers: List<PhotoPublisher>,
    private val media: LocalPublicMediaStorage,
    private val now: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logger = LoggerFactory.getLogger(javaClass)
    private val limiter = CatalogRepostMasterLimiter(settings)
    fun start() {
        scope.launch {
            while (isActive) {
                try {
                    runOnce()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    logger.error("Repost dispatch failed", error)
                }
                delay(1_000.milliseconds)
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

    suspend fun runOnce(): Boolean {
        val config = settings.snapshot()
        val enabled = publishers.filter {
            if (it.destination == RepostDestination.TIKTOK) config.tiktokEnabled else config.threadsEnabled
        }
        if (enabled.isEmpty()) return false
        // The selection is repeated each tick after the persisted limiter releases its pause.
        if (limiter.waitUntilMillis(now()) > 0) return false
        val row = repository.nextRepost(
            enabled.any { it.destination == RepostDestination.TIKTOK },
            enabled.any { it.destination == RepostDestination.THREADS },
        ) ?: return false
        val targets = enabled.filter { repository.status(row, it.destination) == "PENDING" }
        val attemptId = repository.prepare(row.id, targets.map { it.destination }.toSet(), now()) ?: return false
        if (limiter.reserve(attemptId, row.id, now()) > 0) {
            targets.forEach { publisher ->
                repository.changeAttempt(
                    row.id,
                    publisher.destination,
                    attemptId,
                    now()
                ) { it.copy(status = "PENDING") }
            }
            return false
        }
        val photos = Json.decodeFromString<List<CatalogPhoto>>(row.photos)
        for (publisher in targets) {
            val destination = publisher.destination
            try {
                val current = repository.listing(row.id) ?: continue
                if (current.status != "ACTIVE" || current.sourceRevision != row.sourceRevision) {
                    repository.changeAttempt(row.id, destination, attemptId, now()) { it.copy(status = "PENDING") }
                    continue
                }
                check(photos.isNotEmpty() && photos.all { media.resolve(it.fileName) != null }) { "Listing media is unavailable" }
                publisher.awaitPublishSlot()
                repository.changeAttempt(row.id, destination, attemptId, now()) { it.copy(status = "SENDING") }
                val receipt = withContext(PublicationContext(row.id, attemptId)) {
                    publisher.publish(
                        photos.take(publisher.maxPhotoCount).map { media.publicUrl(it.fileName) },
                        caption(row)
                    )
                }
                repository.changeAttempt(row.id, destination, attemptId, now()) {
                    it.copy(
                        status = if (receipt.privacyLevel == "DRAFT" && it.status != "PUBLISHED") "DELIVERED_DRAFT" else "PUBLISHED",
                        publishId = receipt.publishId
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: PublisherBackpressureException) {
                repository.changeAttempt(row.id, destination, attemptId, now()) {
                    it.copy(
                        status = if (it.publishId == null) "PENDING" else it.status,
                        error = error.javaClass.simpleName
                    )
                }
            } catch (error: Throwable) {
                repository.changeAttempt(row.id, destination, attemptId, now()) {
                    // A transport failure after SENDING has an uncertain external outcome.
                    it.copy(
                        status = when {
                            it.publishId != null -> it.status
                            it.status == "SENDING" -> "UNKNOWN"
                            else -> "FAILED"
                        }, error = error.javaClass.simpleName
                    )
                }
                logger.warn(
                    "Repost attempt incomplete. listing={}, destination={}, error={}",
                    row.id,
                    destination,
                    error.javaClass.simpleName
                )
            }
        }
        return true
    }

    private fun caption(row: ListingEntity): String? {
        val details =
            Json.parseToJsonElement(row.primeParams).jsonObject["details"]?.jsonArray?.map { it.jsonPrimitive.content }
                .orEmpty()
        return ListingCaptionFormatter().forTikTok(
            ListingMessage(
                row.title,
                "${requireNotNull(row.price)} ${row.currency}",
                row.address, details, row.description.lines().filter { it.isNotBlank() },
                Json.decodeFromString<List<String>>(row.governmentPrograms).joinToString().takeIf { it.isNotEmpty() },
                null, Json.decodeFromString(row.tags), "066-372-71-02"
            )
        )
    }

    override fun close() {
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
    }
}
