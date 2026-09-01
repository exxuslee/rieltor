package com.rieltor.infrastructure.database

import com.rieltor.domain.model.*
import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.model.ReceivedTelegramMessageEntity
import com.rieltor.infrastructure.database.repository.TelegramRepostQueueImpl
import com.rieltor.infrastructure.database.repository.TelegramRepostRepositoryImpl
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class RoomPersistenceTest {
    @Test
    fun `persistent FIFO queue survives database restart with local photo paths`() {
        val directory = Files.createTempDirectory("rieltor-persistent-queue-test")
        val databasePath = directory.resolve("test.db")
        val photoPath = directory.resolve("photo.jpg")
        Files.write(photoPath, byteArrayOf(1, 2, 3))

        RoomDatabaseStore(databasePath).use { database ->
            val queue = TelegramRepostQueueImpl(database)
            queue.enqueue(
                message(201, TelegramRepostKey(10, "90000:USD", "соборна 1")).copy(
                    photos = listOf(
                        TelegramPhoto("photo.jpg", ByteArrayInputStream(byteArrayOf(1)), photoPath.toString())
                    )
                ),
                64,
            )
        }

        RoomDatabaseStore(databasePath).use { database ->
            val queue = TelegramRepostQueueImpl(database)
            queue.recoverInterrupted()
            assertEquals(listOf(201L), queue.snapshot().pendingUpdateIds)
            val restored = assertNotNull(queue.peekOldest())
            assertEquals(201L, restored.updateId)
            assertEquals(photoPath.toString(), restored.photos.single().localPath)
            assertEquals(201L, queue.snapshot().claimedUpdateId)
            restored.photos.single().content.close()
            queue.complete(201, "PUBLISHED")
            assertNull(queue.peekOldest())
        }
    }

    @Test
    fun `history cleanup removes old terminal rows but preserves queued rows`() {
        database().use { database ->
            val old = 1_000L
            val terminal = ReceivedTelegramMessageEntity(
                301, -1001, 5, "90000:USD", "соборна 1", "caption", "[]",
                "FAILED", null, "error", old, old,
            )
            database.blocking { it.repostQueueDao().reject(terminal, "FAILED", old) }

            val queue = TelegramRepostQueueImpl(database)
            queue.enqueue(message(302, TelegramRepostKey(10, "91000:USD", "соборна 2")), 64)

            assertEquals(1, queue.cleanHistoryBefore(old + 1))
            database.blocking { room ->
                assertNull(room.repostDao().receivedState(301))
                assertNotNull(room.repostDao().receivedState(302))
            }
        }
    }

    @Test
    fun `records received messages and suppresses an already published repost key`() {
        database().use { database ->
            val repository = TelegramRepostRepositoryImpl(database)
            val key = TelegramRepostKey(5242880, "175000:USD", "мечнікова 10")

            assertIs<TelegramMessageRegistration.Accepted>(repository.register(message(101, key), RepostDestination.TIKTOK))
            repository.markRepostPublished(101, RepostDestination.TIKTOK, "publish-101")
            val duplicate = assertIs<TelegramMessageRegistration.Duplicate>(
                repository.register(message(102, key), RepostDestination.TIKTOK)
            )

            assertEquals(101, duplicate.originalUpdateId)
            database.blocking { room ->
                assertEquals(2, room.repostDao().receivedCount())
                val state = assertNotNull(room.repostDao().receivedState(102))
                assertEquals("DUPLICATE", state.status)
                assertEquals(101, state.duplicateOfUpdateId)
                assertEquals("[\"https://drive.google.com/drive/folders/example\"]", state.googleDriveLinks)
                assertEquals("publish-101", room.repostDao().publishId(101, "TIKTOK"))
            }
        }
    }

    @Test
    fun `thread price and address are independent parts of uniqueness key`() {
        database().use { database ->
            val repository = TelegramRepostRepositoryImpl(database)
            val base = TelegramRepostKey(10, "90000:USD", "соборна 1")

            assertIs<TelegramMessageRegistration.Accepted>(repository.register(message(1, base), RepostDestination.TIKTOK))
            assertIs<TelegramMessageRegistration.Accepted>(repository.register(message(2, base.copy(messageThreadId = 11)), RepostDestination.TIKTOK))
            assertIs<TelegramMessageRegistration.Accepted>(repository.register(message(3, base.copy(price = "91000:USD")), RepostDestination.TIKTOK))
            assertIs<TelegramMessageRegistration.Accepted>(repository.register(message(4, base.copy(address = "соборна 2")), RepostDestination.TIKTOK))
            assertIs<TelegramMessageRegistration.Duplicate>(repository.register(message(5, base), RepostDestination.TIKTOK))
        }
    }

    @Test
    fun `failed repost releases identity for retry`() {
        database().use { database ->
            val repository = TelegramRepostRepositoryImpl(database)
            val key = TelegramRepostKey(10, "90000:USD", "соборна 1")

            assertIs<TelegramMessageRegistration.Accepted>(repository.register(message(1, key), RepostDestination.TIKTOK))
            repository.markRepostFailed(1, RepostDestination.TIKTOK, "temporary")
            assertIs<TelegramMessageRegistration.Accepted>(repository.register(message(1, key), RepostDestination.TIKTOK))
        }
    }

    @Test
    fun `destinations keep independent duplicate and retry state`() {
        database().use { database ->
            val repository = TelegramRepostRepositoryImpl(database)
            val key = TelegramRepostKey(10, "90000:USD", "соборна 1")
            val first = message(1, key)

            assertIs<TelegramMessageRegistration.Accepted>(repository.register(first, RepostDestination.TIKTOK))
            assertIs<TelegramMessageRegistration.Accepted>(repository.register(first, RepostDestination.THREADS))
            repository.markRepostPublished(1, RepostDestination.TIKTOK, "tiktok-1")
            repository.markRepostFailed(1, RepostDestination.THREADS, "temporary")

            val repeated = message(2, key)
            assertIs<TelegramMessageRegistration.Duplicate>(repository.register(repeated, RepostDestination.TIKTOK))
            assertIs<TelegramMessageRegistration.Accepted>(repository.register(repeated, RepostDestination.THREADS))
        }
    }

    @Test
    fun `only one concurrent message reserves the same repost key`() {
        database().use { database ->
            val repository = TelegramRepostRepositoryImpl(database)
            val workerCount = 8
            val ready = CountDownLatch(workerCount)
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(workerCount)
            val key = TelegramRepostKey(10, "90000:USD", "соборна 1")

            try {
                val attempts = (1..workerCount).map { updateId ->
                    executor.submit<TelegramMessageRegistration> {
                        ready.countDown()
                        start.await()
                        repository.register(message(updateId.toLong(), key), RepostDestination.TIKTOK)
                    }
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                start.countDown()
                val results = attempts.map { it.get(10, TimeUnit.SECONDS) }
                assertEquals(1, results.count { it is TelegramMessageRegistration.Accepted })
                assertEquals(workerCount - 1, results.count { it is TelegramMessageRegistration.Duplicate })
            } finally {
                start.countDown()
                executor.shutdownNow()
            }
        }
    }

    private fun database() = RoomDatabaseStore(
        Files.createTempDirectory("rieltor-room-test").resolve("test.db")
    )

    private fun message(updateId: Long, key: TelegramRepostKey) = TelegramListing(
        updateId = updateId,
        chatId = -1002681732909,
        messageThreadId = key.messageThreadId,
        caption = "Вул. Соборна 1\nЦіна 90000${'$'}",
        photos = emptyList(),
        googleDriveLinks = listOf("https://drive.google.com/drive/folders/example"),
        repostKey = key,
    )
}
