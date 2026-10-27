package com.rieltor.application.orchestration

import com.rieltor.application.worker.RepostWorker
import com.rieltor.domain.model.*
import com.rieltor.domain.repository.PhotoPublisher
import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.model.AdEntity
import com.rieltor.infrastructure.database.repository.CatalogRepository
import com.rieltor.infrastructure.media.LocalPublicMediaStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IndependentRepostQueuesTest {
    @Test
    fun `threads stops at ten per rolling day while tiktok continues`() = runBlocking {
        Fixture().use { fixture ->
            fixture.worker().use { worker ->
                repeat(11) {
                    worker.runOnce()
                    fixture.now += 3_600_000
                }
                assertEquals(11, fixture.calls.count { it == RepostDestination.TIKTOK })
                assertEquals(10, fixture.calls.count { it == RepostDestination.THREADS })
                assertEquals(11, fixture.db.settings.snapshot().slotReservations.size)
                assertEquals(10, fixture.db.settings.snapshot().threadsSlotReservations.size)

                fixture.now = 5000L + 86_400_000
                worker.runOnce()
                assertEquals(12, fixture.calls.count { it == RepostDestination.TIKTOK })
                assertEquals(11, fixture.calls.count { it == RepostDestination.THREADS })
            }
        }
    }

    @Test
    fun `tiktok cooldown does not stop threads and threads quota does not stop tiktok`() = runBlocking {
        Fixture().use { fixture ->
            fixture.db.settings.update { it.copy(blockedUntil = Long.MAX_VALUE, threadsMaxMessagesPer24Hours = 1) }
            fixture.worker().use { worker ->
                worker.runOnce()
                assertEquals(listOf(RepostDestination.THREADS), fixture.calls.toList())
                assertTrue(fixture.db.settings.snapshot().slotReservations.isEmpty())
                fixture.db.settings.update { it.copy(blockedUntil = 0) }
                worker.runOnce()
                assertEquals(listOf(RepostDestination.THREADS, RepostDestination.TIKTOK), fixture.calls.toList())
            }
        }
    }

    @Test
    fun `threads publishes while tiktok is waiting on its publisher`() = runBlocking {
        Fixture().use { fixture ->
            val tiktokStarted = CompletableDeferred<Unit>()
            val threadsPublished = CompletableDeferred<Unit>()
            fixture.worker(
                awaitTikTok = { tiktokStarted.complete(Unit); CompletableDeferred<Unit>().await() },
                afterThreads = { threadsPublished.complete(Unit) },
            ).use { worker ->
                worker.start()
                withTimeout(10_000) {
                    tiktokStarted.await()
                    threadsPublished.await()
                }
                assertEquals(listOf(RepostDestination.THREADS), fixture.calls.toList())
            }
        }
    }

    private class Fixture : AutoCloseable {
        private val directory = Files.createTempDirectory("independent-reposts")
        val db = RoomDatabaseStore(directory.resolve("test.db"))
        private val repo = CatalogRepository(db)
        private val media = LocalPublicMediaStorage(directory.resolve("media"), "https://media.example")
        val calls = ConcurrentLinkedQueue<RepostDestination>()
        var now = 5000L

        init {
            db.settings.update { it.copy(threadsEnabled = true, minIntervalMs = 0) }
            val name = "00000000-0000-0000-0000-000000000001.jpg"
            Files.write(directory.resolve("media").resolve(name), byteArrayOf(1))
            repeat(12) { index ->
                repo.save(AdEntity(
                    adId = "ad-$index", chatId = REPOST_SOURCE_CHAT_ID, messageId = index + 1L,
                    messageThreadId = 1, sourceRevision = "1", title = "Listing $index",
                    price = 10000, currency = "USD", timestamp = index + 1L, status = ListingStatus.Active,
                    photos = Json.encodeToString(listOf(CatalogPhoto(name, "file", "1", 1, 1, "hash"))),
                ))
            }
        }

        fun worker(awaitTikTok: suspend () -> Unit = {}, afterThreads: () -> Unit = {}) = RepostWorker(
            repo, db.settings, RepostDestination.entries.map { target ->
                object : PhotoPublisher {
                    override val destination = target
                    override val maxPhotoCount = 20
                    override suspend fun awaitPublishSlot() {
                        if (target == RepostDestination.TIKTOK) awaitTikTok()
                    }
                    override suspend fun publish(photoUrls: List<String>, caption: String?): PublishReceipt {
                        calls += target
                        if (target == RepostDestination.THREADS) afterThreads()
                        return PublishReceipt("published-$target", "creator", "PUBLIC", target)
                    }
                }
            }, media,
        ) { now }

        override fun close() = db.close()
    }
}
