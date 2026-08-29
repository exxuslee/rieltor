package com.rieltor.infrastructure.database

import com.rieltor.domain.model.StoredTokens
import com.rieltor.domain.model.StoredGoogleDriveTokens
import com.rieltor.domain.model.ReceivedTelegramMessage
import com.rieltor.domain.model.TelegramMessageRegistration
import com.rieltor.domain.model.TelegramRepostKey
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs

class SqliteRepositoriesTest {
    @Test
    fun `migration removes oauth application credentials from sqlite`() {
        val directory = Files.createTempDirectory("rieltor-db-migration-test")
        val databasePath = directory.resolve("test.db")
        val oldDatabase = SqliteDatabase(databasePath)
        val oldRepository = SqliteRepositories(oldDatabase)
        oldRepository.putIfAbsent("TIKTOK_CLIENT_KEY", "stored-key")
        oldRepository.putIfAbsent("TIKTOK_CLIENT_SECRET", "stored-secret")
        oldRepository.putIfAbsent("GOOGLE_CLIENT_ID", "stored-google-id")
        oldRepository.putIfAbsent("GOOGLE_CLIENT_SECRET", "stored-google-secret")
        oldDatabase.connection().use { connection ->
            connection.createStatement().use { it.execute("PRAGMA user_version=1") }
        }

        val migratedRepository = SqliteRepositories(SqliteDatabase(databasePath))

        assertNull(migratedRepository.get("TIKTOK_CLIENT_KEY"))
        assertNull(migratedRepository.get("TIKTOK_CLIENT_SECRET"))
        assertNull(migratedRepository.get("GOOGLE_CLIENT_ID"))
        assertNull(migratedRepository.get("GOOGLE_CLIENT_SECRET"))
    }

    @Test
    fun `stores secrets tokens and idempotent jobs`() {
        val directory = Files.createTempDirectory("rieltor-db-test")
        val repository = SqliteRepositories(SqliteDatabase(directory.resolve("test.db")))

        repository.putIfAbsent("secret", "first")
        repository.putIfAbsent("secret", "second")
        assertEquals("first", repository.get("secret"))

        val tokens = StoredTokens("open", "access", "refresh", 100, 200)
        repository.save(tokens)
        assertEquals(tokens, repository.latest())

        val googleTokens = StoredGoogleDriveTokens("google-access", "google-refresh", 300)
        repository.save(googleTokens)
        assertEquals(googleTokens, repository.load())

        assertTrue(repository.tryStart(7))
        assertFalse(repository.tryStart(7))
        repository.markFailed(7, "temporary")
        assertTrue(repository.tryStart(7))
        repository.markPublished(7, "publish-id")
        assertFalse(repository.tryStart(7))
    }

    @Test
    fun `only one concurrent worker starts the same job`() {
        val directory = Files.createTempDirectory("rieltor-db-concurrency-test")
        val repository = SqliteRepositories(SqliteDatabase(directory.resolve("test.db")))
        val workerCount = 8
        val ready = CountDownLatch(workerCount)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(workerCount)

        try {
            val attempts = List(workerCount) {
                executor.submit<Boolean> {
                    ready.countDown()
                    start.await()
                    repository.tryStart(42)
                }
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()

            val accepted = attempts.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, accepted.count { it })
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `records received messages and suppresses an already published repost key`() {
        val directory = Files.createTempDirectory("rieltor-repost-history-test")
        val database = SqliteDatabase(directory.resolve("test.db"))
        val repository = SqliteRepositories(database)
        val key = TelegramRepostKey(5242880, "175000:USD", "мечнікова 10")

        assertIs<TelegramMessageRegistration.Accepted>(repository.register(message(101, key)))
        repository.markRepostPublished(101, "publish-101")
        val duplicate = assertIs<TelegramMessageRegistration.Duplicate>(
            repository.register(message(102, key))
        )

        assertEquals(101, duplicate.originalUpdateId)
        database.connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT COUNT(*) FROM received_telegram_messages"
                ).use { result ->
                    assertTrue(result.next())
                    assertEquals(2, result.getInt(1))
                }
                statement.executeQuery(
                    "SELECT status, duplicate_of_update_id FROM received_telegram_messages " +
                        "WHERE telegram_update_id = 102"
                ).use { result ->
                    assertTrue(result.next())
                    assertEquals("DUPLICATE", result.getString("status"))
                    assertEquals(101, result.getLong("duplicate_of_update_id"))
                }
                statement.executeQuery(
                    "SELECT publish_id FROM published_reposts WHERE telegram_update_id = 101"
                ).use { result ->
                    assertTrue(result.next())
                    assertEquals("publish-101", result.getString("publish_id"))
                }
            }
        }
    }

    @Test
    fun `thread price and address are independent parts of uniqueness key`() {
        val directory = Files.createTempDirectory("rieltor-repost-key-test")
        val repository = SqliteRepositories(SqliteDatabase(directory.resolve("test.db")))
        val base = TelegramRepostKey(10, "90000:USD", "соборна 1")

        assertIs<TelegramMessageRegistration.Accepted>(repository.register(message(1, base)))
        assertIs<TelegramMessageRegistration.Accepted>(repository.register(message(2, base.copy(messageThreadId = 11))))
        assertIs<TelegramMessageRegistration.Accepted>(repository.register(message(3, base.copy(price = "91000:USD"))))
        assertIs<TelegramMessageRegistration.Accepted>(repository.register(message(4, base.copy(address = "соборна 2"))))
        assertIs<TelegramMessageRegistration.Duplicate>(repository.register(message(5, base)))
    }

    @Test
    fun `failed repost releases identity for retry`() {
        val directory = Files.createTempDirectory("rieltor-repost-retry-test")
        val repository = SqliteRepositories(SqliteDatabase(directory.resolve("test.db")))
        val key = TelegramRepostKey(10, "90000:USD", "соборна 1")

        assertIs<TelegramMessageRegistration.Accepted>(repository.register(message(1, key)))
        repository.markRepostFailed(1, "temporary")

        assertIs<TelegramMessageRegistration.Accepted>(repository.register(message(1, key)))
    }

    @Test
    fun `only one concurrent message reserves the same repost key`() {
        val directory = Files.createTempDirectory("rieltor-repost-concurrency-test")
        val repository = SqliteRepositories(SqliteDatabase(directory.resolve("test.db")))
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
                    repository.register(message(updateId.toLong(), key))
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

    private fun message(updateId: Long, key: TelegramRepostKey) = ReceivedTelegramMessage(
        updateId = updateId,
        chatId = -1002681732909,
        messageThreadId = key.messageThreadId,
        caption = "Вул. Соборна 1\nЦіна 90000${'$'}",
        repostKey = key,
    )
}
