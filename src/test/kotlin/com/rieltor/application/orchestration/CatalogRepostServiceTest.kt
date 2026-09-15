package com.rieltor.application.orchestration

import com.rieltor.domain.model.CatalogPhoto
import com.rieltor.domain.model.PublishReceipt
import com.rieltor.domain.model.RepostDestination
import com.rieltor.domain.repository.PhotoPublisher
import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.model.ListingEntity
import com.rieltor.infrastructure.database.repository.CatalogRepository
import com.rieltor.infrastructure.media.LocalPublicMediaStorage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogRepostServiceTest {
    @Test fun `newest after cooldown publishes and failed destination does not repeat successful one`() = runBlocking {
        val directory = Files.createTempDirectory("repost-newest")
        RoomDatabaseStore(directory.resolve("test.db")).use { db ->
            db.settings.update { it.copy(threadsEnabled = true, minIntervalMs = 0, blockedUntil = 5000) }
            val repo = CatalogRepository(db)
            val media = LocalPublicMediaStorage(directory.resolve("media"), "https://media.example")
            val fileName = "00000000-0000-0000-0000-000000000001.jpg"
            Files.write(directory.resolve("media").resolve(fileName), byteArrayOf(1))
            fun add(message: Long) = repo.save(ListingEntity(groupKey = "g$message", chatId = -1, messageId = message, messageThreadId = 1,
                rawMessage = "private", sourceRevision = "1", title = "Listing $message", price = 10000, currency = "USD",
                sourceCreatedAt = message, receivedAt = message, cdt = message, updatedAt = message, status = "ACTIVE",
                photos = Json.encodeToString(listOf(CatalogPhoto(fileName, "file", "1", 1, 1, "hash")))))
            add(1)
            val captions = mutableListOf<String>()
            val tiktok = object : PhotoPublisher {
                override val destination = RepostDestination.TIKTOK; override val maxPhotoCount = 20
                override suspend fun publish(photoUrls: List<String>, caption: String?): PublishReceipt {
                    captions += caption.orEmpty(); return PublishReceipt("published", "creator", "SELF_ONLY")
                }
            }
            val threads = object : PhotoPublisher {
                override val destination = RepostDestination.THREADS; override val maxPhotoCount = 20
                override suspend fun publish(photoUrls: List<String>, caption: String?): PublishReceipt = error("uncertain external response")
            }
            var now = 1000L
            val service = CatalogRepostService(repo, db.settings, listOf(tiktok, threads), media) { now }
            assertFalse(service.runOnce()); assertTrue(db.settings.snapshot().slotReservations.isEmpty())
            val newest = add(2); now = 5000
            assertTrue(service.runOnce()); assertTrue(captions.single().contains("Listing 2"))
            assertTrue(repo.listing(newest)!!.tiktokReposted); assertFalse(repo.listing(newest)!!.threadsReposted)
            assertEquals("UNKNOWN", repo.listing(newest)!!.threadsStatus)
            assertTrue(service.runOnce()); assertEquals(2, captions.size)
            assertFalse(service.runOnce()); assertEquals(2, captions.size)
            service.close()
        }
    }
}
