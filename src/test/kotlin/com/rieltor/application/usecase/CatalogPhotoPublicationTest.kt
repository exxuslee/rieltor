package com.rieltor.application.usecase

import com.rieltor.application.worker.RepostWorker
import com.rieltor.domain.model.*
import com.rieltor.domain.repository.PhotoPublisher
import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.model.AdEntity
import com.rieltor.infrastructure.database.repository.CatalogRepository
import com.rieltor.infrastructure.media.LocalPublicMediaStorage
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CatalogPhotoPublicationTest {
    @Test fun `publishes ordered album once and respects destination photo limits`() = runBlocking {
        withListing { repo, id, worker, calls ->
            worker.runOnce()
            assertEquals(listOf(RepostDestination.TIKTOK, RepostDestination.THREADS), calls.map { it.first })
            assertEquals(listOf(2, 3), calls.map { it.second.size })
            assertEquals(calls[1].second.take(2), calls[0].second)
            assertTrue(calls.all { it.third.contains("Listing") && it.third.contains("test-contact") })
            assertNotNull(repo.repost(id)?.tiktokRepostedAt)
            assertNotNull(repo.repost(id)?.threadsRepostedAt)
            worker.runOnce()
            assertEquals(2, calls.size)
        }
    }

    @Test fun `first destination failure does not cancel second or retry uncertain send`() = runBlocking {
        withListing(failFirst = true) { repo, id, worker, calls ->
            worker.runOnce()
            assertEquals(listOf(RepostDestination.TIKTOK, RepostDestination.THREADS), calls.map { it.first })
            val repost = assertNotNull(repo.repost(id))
            assertEquals(RepostStatus.Unknown, repo.status(repost, RepostDestination.TIKTOK))
            assertEquals(RepostStatus.Published, repo.status(repost, RepostDestination.THREADS))
            worker.runOnce()
            assertEquals(2, calls.size)
        }
    }

    @Test fun `disabled destinations do not publish or reserve quota`() = runBlocking {
        withListing(disabled = true) { repo, id, worker, calls ->
            worker.runOnce()
            assertTrue(calls.isEmpty())
            assertEquals(RepostStatus.Pending, repo.status(assertNotNull(repo.repost(id)), RepostDestination.TIKTOK))
        }
    }

    private suspend fun withListing(
        failFirst: Boolean = false, disabled: Boolean = false,
        test: suspend (CatalogRepository, Long, RepostWorker, MutableList<Triple<RepostDestination, List<String>, String>>) -> Unit,
    ) {
        val directory = Files.createTempDirectory("catalog-publication")
        RoomDatabaseStore(directory.resolve("test.db")).use { db ->
            db.settings.update { it.copy(minIntervalMs = 0, tiktokEnabled = !disabled,
                threadsEnabled = !disabled, repostContactPhone = "test-contact") }
            val repo = CatalogRepository(db)
            val media = LocalPublicMediaStorage(directory.resolve("media"), "https://media.example")
            val photos = (1..3).map { n ->
                val name = "00000000-0000-0000-0000-00000000000$n.jpg"
                Files.write(directory.resolve("media").resolve(name), byteArrayOf(n.toByte()))
                CatalogPhoto(name, "$n", "1", 1, 1, "hash$n")
            }
            val id = repo.save(AdEntity(adId = "ad", chatId = -1, messageId = 1, messageThreadId = 1,
                sourceRevision = "1", title = "Listing", price = 80000, currency = "USD",
                timestamp = 1000, status = ListingStatus.Active, photos = Json.encodeToString(photos)))
            val calls = mutableListOf<Triple<RepostDestination, List<String>, String>>()
            var active = 0
            var maximumActive = 0
            val publishers = RepostDestination.entries.map { destination ->
                object : PhotoPublisher {
                    override val destination = destination
                    override val maxPhotoCount = if (destination == RepostDestination.TIKTOK) 2 else 3
                    override suspend fun publish(photoUrls: List<String>, caption: String?): PublishReceipt {
                        active++
                        maximumActive = maxOf(maximumActive, active)
                        try {
                            calls += Triple(destination, photoUrls, caption.orEmpty())
                            delay(10)
                            if (failFirst && destination == RepostDestination.TIKTOK) error("response lost")
                            return PublishReceipt("published-$destination", "creator", "SELF_ONLY", destination)
                        } finally { active-- }
                    }
                }
            }
            RepostWorker(repo, db.settings, publishers, media) { 5000L }.use { worker ->
                test(repo, id, worker, calls)
                assertEquals(if (disabled) 0 else 1, maximumActive)
                assertEquals(if (disabled) 0 else 1, db.settings.snapshot().slotReservations.size)
            }
        }
    }
}
