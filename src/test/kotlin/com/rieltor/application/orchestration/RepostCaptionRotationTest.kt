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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RepostCaptionRotationTest {
    @Test fun `publishers cycle through conversational and balanced variants without changing the main cycle`() = runBlocking {
        Fixture(listingCount = 14).use { fixture ->
            fixture.worker().use { it.runOnce() }
            fixture.db.settings.update { it.copy(threadsEnabled = true, threadsMaxMessagesPer24Hours = 20) }
            fixture.worker().use { worker -> repeat(6) { worker.runOnce() } }
            fixture.worker().use { worker -> repeat(6) { worker.runOnce() } }
            val tiktok = fixture.captions.getValue(RepostDestination.TIKTOK)
            val threads = fixture.captions.getValue(RepostDestination.THREADS)
            assertEquals(List(13) { it % 3 + 1 }, tiktok.map(::styleOf))
            assertEquals(List(12) { it % 3 + 1 }, threads.map(::styleOf))
            val expected = listOf("Кому ", "Шукаєте ", "Покажу вам ще один варіант.", "Кому ")
            for (captions in listOf(tiktok, threads)) {
                val conversations = captions.filter { styleOf(it) == 2 }
                assertEquals(4, conversations.size)
                conversations.zip(expected).forEach { (text, opening) -> assertTrue(text.startsWith(opening), text) }
                val balanced = captions.filter { styleOf(it) == 3 }
                assertEquals(4, balanced.size)
                val balancedOpenings = listOf("🏠 ", "Коротко про об’єкт:", "Варіант для розгляду:", "🏠 ")
                balanced.zip(balancedOpenings).forEach { (text, opening) -> assertTrue(text.startsWith(opening), text) }
            }
        }
    }

    @Test fun `each publisher rotates independently and manual TikTok uses the same sequence`() = runBlocking {
        Fixture().use { fixture ->
            fixture.worker().use { it.runOnce() }
            fixture.db.settings.update { it.copy(threadsEnabled = true) }
            // A new worker must continue the persisted sequence.
            fixture.worker().use { worker ->
                repeat(3) { worker.runOnce() }
                worker.repostTikTok(fixture.firstId)
            }
            assertEquals(listOf(1, 2, 3, 1, 2), fixture.captions.getValue(RepostDestination.TIKTOK).map(::styleOf))
            assertEquals(listOf(1, 2, 3), fixture.captions.getValue(RepostDestination.THREADS).map(::styleOf))
        }
    }

    @Test fun `backpressure before sending and invalid media do not advance style`() = runBlocking {
        Fixture().use { fixture ->
            fixture.blocked = true
            fixture.worker().use { worker ->
                worker.runOnce()
                assertEquals(RepostCaptionStyle.STANDARD, fixture.db.settings.snapshot().tiktokCaptionRotation.nextStyle)
                fixture.blocked = false
                worker.runOnce()
                assertEquals(1, styleOf(fixture.captions.getValue(RepostDestination.TIKTOK).single()))
                Files.delete(fixture.photoPath)
                worker.runOnce()
                assertEquals(RepostCaptionStyle.CONVERSATIONAL, fixture.db.settings.snapshot().tiktokCaptionRotation.nextStyle)
            }
        }
    }

    @Test fun `uncertain send consumes one style and is not sent again`() = runBlocking {
        Fixture().use { fixture ->
            fixture.failSend = true
            fixture.worker().use { worker ->
                worker.runOnce()
                fixture.failSend = false
                worker.runOnce()
            }
            assertEquals(listOf(1, 2), fixture.captions.getValue(RepostDestination.TIKTOK).map(::styleOf))
        }
    }

    private fun styleOf(text: String): Int = when {
        text.startsWith("Кому ") || text.startsWith("Шукаєте ") || text.startsWith("Покажу вам ще один варіант.") -> 2
        text.contains("Напишіть, щоб дізнатися деталі") || text.startsWith("Коротко про об’єкт:") ||
            text.startsWith("Варіант для розгляду:") -> 3
        else -> 1
    }

    private class Fixture(listingCount: Int = 6) : AutoCloseable {
        private val directory = Files.createTempDirectory("repost-captions")
        val db = RoomDatabaseStore(directory.resolve("test.db"))
        private val repo = CatalogRepository(db)
        private val media = LocalPublicMediaStorage(directory.resolve("media"), "https://media.example")
        private val name = "00000000-0000-0000-0000-000000000001.jpg"
        val photoPath = directory.resolve("media").resolve(name)
        val captions = RepostDestination.entries.associateWith { mutableListOf<String>() }
        var blocked = false
        var failSend = false
        val firstId: Long

        init {
            db.settings.update { it.copy(minIntervalMs = 0) }
            Files.write(photoPath, byteArrayOf(1))
            val ids = (1..listingCount).map { index -> repo.save(AdEntity(
                adId = "ad-$index", chatId = REPOST_SOURCE_CHAT_ID, messageId = index.toLong(),
                messageThreadId = 1, sourceRevision = "1", title = "Продам будинок", location = "HOSTOMEL",
                price = 10000, currency = "USD", timestamp = index.toLong(), status = ListingStatus.Active,
                photos = Json.encodeToString(listOf(CatalogPhoto(name, "file", "1", 1, 1, "hash"))),
            )) }
            firstId = ids.first()
        }

        fun worker() = RepostWorker(repo, db.settings, RepostDestination.entries.map { target ->
            object : PhotoPublisher {
                override val destination = target
                override val maxPhotoCount = 20
                override suspend fun awaitPublishSlot() {
                    if (blocked) throw PublisherBackpressureException("Try later")
                }
                override suspend fun publish(photoUrls: List<String>, caption: String?): PublishReceipt {
                    captions.getValue(target) += checkNotNull(caption)
                    if (failSend) error("Response lost")
                    return PublishReceipt("publish-${captions.getValue(target).size}", "creator", "PUBLIC", target)
                }
            }
        }, media) { 5000L }

        override fun close() = db.close()
    }
}
