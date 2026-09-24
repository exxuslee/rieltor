package com.rieltor.application.orchestration

import com.rieltor.application.worker.RepostWorker
import com.rieltor.domain.model.*
import com.rieltor.domain.repository.PhotoPublisher
import com.rieltor.domain.repository.PublisherBackpressureException
import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.model.AdEntity
import com.rieltor.infrastructure.database.repository.CatalogRepository
import com.rieltor.infrastructure.media.LocalPublicMediaStorage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.*

class RepostWorkerTest {
    @Test
    fun `invalid media or caption fails before sending and does not block other listings`() = runBlocking {
        for (invalidPhotos in listOf(true, false)) {
            val directory = Files.createTempDirectory("repost-invalid")
            RoomDatabaseStore(directory.resolve("test.db")).use { db ->
                db.settings.update { it.copy(minIntervalMs = 0) }
                val repo = CatalogRepository(db)
                val media = LocalPublicMediaStorage(directory.resolve("media"), "https://media.example")
                val fileName = "00000000-0000-0000-0000-000000000001.jpg"
                Files.write(directory.resolve("media").resolve(fileName), byteArrayOf(1))
                val valid = AdEntity(
                    adId = "valid", chatId = -1, messageId = 1, messageThreadId = 1,
                    sourceRevision = "1", title = "Valid", price = 10000, currency = "USD",
                    sourceCreatedAt = 1, createdAt = 1, updatedAt = 1, status = ListingStatus.Active,
                    photos = Json.encodeToString(listOf(CatalogPhoto(fileName, "file", "1", 1, 1, "hash")))
                )
                val validId = repo.save(valid)
                val invalidId = repo.save(valid.copy(
                    adId = "invalid", messageId = 2, sourceCreatedAt = 2,
                    photos = if (invalidPhotos) "broken" else valid.photos,
                    primeParams = if (invalidPhotos) valid.primeParams else "broken",
                ))
                var calls = 0
                val publisher = object : PhotoPublisher {
                    override val destination = RepostDestination.TIKTOK
                    override val maxPhotoCount = 20
                    override suspend fun publish(photoUrls: List<String>, caption: String?): PublishReceipt {
                        calls++
                        return PublishReceipt("published", "creator", "SELF_ONLY")
                    }
                }
                RepostWorker(repo, db.settings, listOf(publisher), media) { 5000L }.use { worker ->
                    worker.runOnce()
                    assertEquals(0, calls)
                    assertEquals(RepostStatus.Failed, repo.status(repo.repost(invalidId)!!, publisher.destination))
                    worker.runOnce()
                    assertEquals(1, calls)
                    assertEquals(RepostStatus.Published, repo.status(repo.repost(validId)!!, publisher.destination))
                    worker.runOnce()
                    assertEquals(1, calls)
                }
            }
        }
    }

    @Test
    fun `backpressure retries after cooldown and draft is not republished`() = runBlocking {
        val directory = Files.createTempDirectory("repost-backpressure")
        RoomDatabaseStore(directory.resolve("test.db")).use { db ->
            db.settings.update { it.copy(minIntervalMs = 1000) }
            val repo = CatalogRepository(db)
            val media = LocalPublicMediaStorage(directory.resolve("media"), "https://media.example")
            val fileName = "00000000-0000-0000-0000-000000000001.jpg"
            Files.write(directory.resolve("media").resolve(fileName), byteArrayOf(1))
            val id = repo.save(AdEntity(
                adId = "draft", chatId = -1, messageId = 1, messageThreadId = 1,
                sourceRevision = "1", title = "Draft", price = 10000, currency = "USD",
                sourceCreatedAt = 1, createdAt = 1, updatedAt = 1, status = ListingStatus.Active,
                photos = Json.encodeToString(listOf(CatalogPhoto(fileName, "file", "1", 1, 1, "hash")))
            ))
            var blocked = true
            var calls = 0
            val publisher = object : PhotoPublisher {
                override val destination = RepostDestination.TIKTOK
                override val maxPhotoCount = 20
                override suspend fun awaitPublishSlot() {
                    if (blocked) throw PublisherBackpressureException("Try later")
                }
                override suspend fun publish(photoUrls: List<String>, caption: String?): PublishReceipt {
                    calls++
                    return PublishReceipt("draft-id", "creator", "DRAFT")
                }
            }
            var now = 5000L
            RepostWorker(repo, db.settings, listOf(publisher), media) { now }.use { worker ->
                worker.runOnce()
                assertEquals(RepostStatus.Pending, repo.status(repo.repost(id)!!, publisher.destination))
                assertEquals(0, calls)
                blocked = false
                worker.runOnce()
                assertEquals(0, calls)
                assertEquals(RepostStatus.Pending, repo.status(repo.repost(id)!!, publisher.destination))
                now += 1000
                worker.runOnce()
                assertEquals(RepostStatus.DeliveredDraft, repo.status(repo.repost(id)!!, publisher.destination))
                assertEquals(1, calls)
                now += 1000
                worker.runOnce()
                assertEquals(1, calls)
            }
        }
    }

    @Test
    fun `newest after cooldown publishes and failed destination does not repeat successful one`() = runBlocking {
        val directory = Files.createTempDirectory("repost-newest")
        RoomDatabaseStore(directory.resolve("test.db")).use { db ->
            db.settings.update { it.copy(threadsEnabled = true, minIntervalMs = 0, blockedUntil = 5000,
                repostContactPhone = "test-contact") }
            val repo = CatalogRepository(db)
            val media = LocalPublicMediaStorage(directory.resolve("media"), "https://media.example")
            val fileName = "00000000-0000-0000-0000-000000000001.jpg"
            Files.write(directory.resolve("media").resolve(fileName), byteArrayOf(1))
            fun add(message: Long) = repo.save(
                AdEntity(
                    adId = "ad$message", chatId = -1, messageId = message, messageThreadId = 1,
                    sourceRevision = "1", title = "Listing $message", price = 10000, currency = "USD",
                    sourceCreatedAt = message, createdAt = message, updatedAt = message, status = ListingStatus.Active,
                    photos = Json.encodeToString(listOf(CatalogPhoto(fileName, "file", "1", 1, 1, "hash")))
                )
            )
            add(1)
            val captions = mutableListOf<String>()
            val tiktok = object : PhotoPublisher {
                override val destination = RepostDestination.TIKTOK;
                override val maxPhotoCount = 20
                override suspend fun publish(photoUrls: List<String>, caption: String?): PublishReceipt {
                    captions += caption.orEmpty(); return PublishReceipt("published", "creator", "SELF_ONLY")
                }
            }
            val threads = object : PhotoPublisher {
                override val destination = RepostDestination.THREADS;
                override val maxPhotoCount = 20
                override suspend fun publish(photoUrls: List<String>, caption: String?): PublishReceipt =
                    error("uncertain external response")
            }
            var now = 1000L
            val service = RepostWorker(repo, db.settings, listOf(tiktok, threads), media) { now }
            service.runOnce(); assertTrue(db.settings.snapshot().slotReservations.isEmpty())
            assertTrue(captions.isEmpty())
            val newest = add(2); now = 5000
            service.runOnce(); assertTrue(captions.single().contains("Listing 2"))
            assertTrue(captions.single().contains("test-contact"))
            assertNotNull(repo.repost(newest)!!.tiktokRepostedAt); assertNull(repo.repost(newest)!!.threadsRepostedAt)
            assertEquals(RepostStatus.Unknown, repo.status(repo.repost(newest)!!, RepostDestination.THREADS))
            service.runOnce(); assertEquals(2, captions.size)
            service.runOnce(); assertEquals(2, captions.size)
            service.close()
        }
    }
}
