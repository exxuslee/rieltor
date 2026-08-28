package com.rieltor.infrastructure.database

import com.rieltor.domain.model.StoredTokens
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqliteRepositoriesTest {
    @Test
    fun `migration removes tiktok application credentials from sqlite`() {
        val directory = Files.createTempDirectory("rieltor-db-migration-test")
        val databasePath = directory.resolve("test.db")
        val oldDatabase = SqliteDatabase(databasePath)
        val oldRepository = SqliteRepositories(oldDatabase)
        oldRepository.putIfAbsent("TIKTOK_CLIENT_KEY", "stored-key")
        oldRepository.putIfAbsent("TIKTOK_CLIENT_SECRET", "stored-secret")
        oldDatabase.connection().use { connection ->
            connection.createStatement().use { it.execute("PRAGMA user_version=1") }
        }

        val migratedRepository = SqliteRepositories(SqliteDatabase(databasePath))

        assertNull(migratedRepository.get("TIKTOK_CLIENT_KEY"))
        assertNull(migratedRepository.get("TIKTOK_CLIENT_SECRET"))
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
}
