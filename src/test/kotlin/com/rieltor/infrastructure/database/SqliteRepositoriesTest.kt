package com.rieltor.infrastructure.database

import com.rieltor.domain.model.StoredTokens
import com.rieltor.domain.model.StoredGoogleDriveTokens
import com.rieltor.domain.model.ReceivedTelegramMessage
import com.rieltor.domain.model.TelegramMessageRegistration
import com.rieltor.domain.model.TelegramRepostKey
import com.rieltor.domain.model.RepostDestination
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs

class SqlitePersistenceTest {
    @Test
    fun `migration removes environment-only settings from sqlite`() {
        val directory = Files.createTempDirectory("rieltor-db-migration-test")
        val databasePath = directory.resolve("test.db")
        val oldDatabase = SqliteDatabase(databasePath)
        val oldRepository = SecretRepositoryImpl(oldDatabase)
        oldRepository.putIfAbsent("TIKTOK_CLIENT_KEY", "stored-key")
        oldRepository.putIfAbsent("TIKTOK_CLIENT_SECRET", "stored-secret")
        oldRepository.putIfAbsent("GOOGLE_CLIENT_ID", "stored-google-id")
        oldRepository.putIfAbsent("GOOGLE_CLIENT_SECRET", "stored-google-secret")
        oldRepository.putIfAbsent("TELEGRAM_USER_ID", "530666333")
        oldDatabase.connection().use { connection ->
            connection.createStatement().use { it.execute("PRAGMA user_version=1") }
        }

        val migratedRepository = SecretRepositoryImpl(SqliteDatabase(databasePath))

        assertNull(migratedRepository.get("TIKTOK_CLIENT_KEY"))
        assertNull(migratedRepository.get("TIKTOK_CLIENT_SECRET"))
        assertNull(migratedRepository.get("GOOGLE_CLIENT_ID"))
        assertNull(migratedRepository.get("GOOGLE_CLIENT_SECRET"))
        assertNull(migratedRepository.get("TELEGRAM_USER_ID"))
    }

    @Test
    fun `migration from schema four removes telegram user id`() {
        val directory = Files.createTempDirectory("rieltor-db-v4-migration-test")
        val databasePath = directory.resolve("test.db")
        val oldDatabase = SqliteDatabase(databasePath)
        val oldRepository = SecretRepositoryImpl(oldDatabase)
        oldRepository.putIfAbsent("TELEGRAM_USER_ID", "530666333")
        oldDatabase.connection().use { connection ->
            connection.createStatement().use { it.execute("PRAGMA user_version=4") }
        }

        val migratedDatabase = SqliteDatabase(databasePath)
        val migratedRepository = SecretRepositoryImpl(migratedDatabase)

        assertNull(migratedRepository.get("TELEGRAM_USER_ID"))
        migratedDatabase.connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { result ->
                    assertTrue(result.next())
                    assertEquals(6, result.getInt(1))
                }
            }
        }
    }

    @Test
    fun `stores secrets and oauth tokens`() {
        val directory = Files.createTempDirectory("rieltor-db-test")
        val database = SqliteDatabase(directory.resolve("test.db"))
        val secrets = SecretRepositoryImpl(database)
        val tikTokTokens = TikTokTokenRepositoryImpl(database)
        val googleTokens = GoogleDriveTokenRepositoryImpl(database)

        secrets.putIfAbsent("secret", "first")
        secrets.putIfAbsent("secret", "second")
        assertEquals("first", secrets.get("secret"))

        val tokens = StoredTokens("open", "access", "refresh", 100, 200)
        tikTokTokens.save(tokens)
        assertEquals(tokens, tikTokTokens.latest())

        val storedGoogleTokens = StoredGoogleDriveTokens("google-access", "google-refresh", 300)
        googleTokens.save(storedGoogleTokens)
        assertEquals(storedGoogleTokens, googleTokens.load())
    }

    @Test
    fun `records received messages and suppresses an already published repost key`() {
        val directory = Files.createTempDirectory("rieltor-repost-history-test")
        val database = SqliteDatabase(directory.resolve("test.db"))
        val repository = TelegramRepostRepositoryImpl(database)
        val key = TelegramRepostKey(5242880, "175000:USD", "мечнікова 10")

        assertIs<TelegramMessageRegistration.Accepted>(repository.register(message(101, key), RepostDestination.TIKTOK))
        repository.markRepostPublished(101, RepostDestination.TIKTOK, "publish-101")
        val duplicate = assertIs<TelegramMessageRegistration.Duplicate>(
            repository.register(message(102, key), RepostDestination.TIKTOK)
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
                    "SELECT publish_id FROM repost_publications WHERE telegram_update_id = 101 AND destination = 'TIKTOK'"
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
        val repository = TelegramRepostRepositoryImpl(SqliteDatabase(directory.resolve("test.db")))
        val base = TelegramRepostKey(10, "90000:USD", "соборна 1")

        assertIs<TelegramMessageRegistration.Accepted>(repository.register(message(1, base), RepostDestination.TIKTOK))
        assertIs<TelegramMessageRegistration.Accepted>(repository.register(message(2, base.copy(messageThreadId = 11)), RepostDestination.TIKTOK))
        assertIs<TelegramMessageRegistration.Accepted>(repository.register(message(3, base.copy(price = "91000:USD")), RepostDestination.TIKTOK))
        assertIs<TelegramMessageRegistration.Accepted>(repository.register(message(4, base.copy(address = "соборна 2")), RepostDestination.TIKTOK))
        assertIs<TelegramMessageRegistration.Duplicate>(repository.register(message(5, base), RepostDestination.TIKTOK))
    }

    @Test
    fun `failed repost releases identity for retry`() {
        val directory = Files.createTempDirectory("rieltor-repost-retry-test")
        val repository = TelegramRepostRepositoryImpl(SqliteDatabase(directory.resolve("test.db")))
        val key = TelegramRepostKey(10, "90000:USD", "соборна 1")

        assertIs<TelegramMessageRegistration.Accepted>(repository.register(message(1, key), RepostDestination.TIKTOK))
        repository.markRepostFailed(1, RepostDestination.TIKTOK, "temporary")

        assertIs<TelegramMessageRegistration.Accepted>(repository.register(message(1, key), RepostDestination.TIKTOK))
    }

    @Test
    fun `destinations keep independent duplicate and retry state`() {
        val directory = Files.createTempDirectory("rieltor-repost-destinations-test")
        val repository = TelegramRepostRepositoryImpl(SqliteDatabase(directory.resolve("test.db")))
        val key = TelegramRepostKey(10, "90000:USD", "соборна 1")
        val first = message(1, key)

        assertIs<TelegramMessageRegistration.Accepted>(repository.register(first, RepostDestination.TIKTOK))
        assertIs<TelegramMessageRegistration.Accepted>(repository.register(first, RepostDestination.THREADS))
        repository.markRepostPublished(1, RepostDestination.TIKTOK, "tiktok-1")
        repository.markRepostFailed(1, RepostDestination.THREADS, "temporary")

        val repeated = message(2, key)
        assertIs<TelegramMessageRegistration.Duplicate>(
            repository.register(repeated, RepostDestination.TIKTOK)
        )
        assertIs<TelegramMessageRegistration.Accepted>(
            repository.register(repeated, RepostDestination.THREADS)
        )
    }

    @Test
    fun `only one concurrent message reserves the same repost key`() {
        val directory = Files.createTempDirectory("rieltor-repost-concurrency-test")
        val repository = TelegramRepostRepositoryImpl(SqliteDatabase(directory.resolve("test.db")))
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

    private fun message(updateId: Long, key: TelegramRepostKey) = ReceivedTelegramMessage(
        updateId = updateId,
        chatId = -1002681732909,
        messageThreadId = key.messageThreadId,
        caption = "Вул. Соборна 1\nЦіна 90000${'$'}",
        repostKey = key,
    )
}
