package com.rieltor.application.orchestration

import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.repository.TikTokPublishThrottleRepositoryImpl
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class RepostMasterLimiterTest {
    @Test
    fun `shared TikTok block pauses the global FIFO master limiter`() = runBlocking {
        val path = Files.createTempDirectory("master-global-block-test").resolve("rieltor.db")
        var clock = 1_000L
        val delays = mutableListOf<Long>()

        RoomDatabaseStore(path).use { database ->
            val repository = TikTokPublishThrottleRepositoryImpl(database)
            repository.blockUntil(clock + 86_400_000L)
            PersistentRepostMasterLimiter(
                repository = repository,
                maxMessagesPer24Hours = 36,
                minIntervalMillis = 0,
                nowMillis = { clock },
                delayMillis = { wait -> delays += wait; clock += wait },
            ).awaitSlot()
        }

        assertEquals(listOf(86_400_000L), delays)
    }

    @Test
    fun `master limiter spaces FIFO messages and enforces a rolling window after restart`() = runBlocking {
        val path = Files.createTempDirectory("master-limiter-test").resolve("rieltor.db")
        var clock = 0L
        val delays = mutableListOf<Long>()

        RoomDatabaseStore(path).use { database ->
            val limiter = PersistentRepostMasterLimiter(
                repository = TikTokPublishThrottleRepositoryImpl(database),
                maxMessagesPer24Hours = 2,
                minIntervalMillis = 10,
                nowMillis = { clock },
                delayMillis = { wait -> delays += wait; clock += wait },
            )
            limiter.awaitSlot()
            limiter.awaitSlot()
        }
        RoomDatabaseStore(path).use { database ->
            PersistentRepostMasterLimiter(
                repository = TikTokPublishThrottleRepositoryImpl(database),
                maxMessagesPer24Hours = 2,
                minIntervalMillis = 10,
                nowMillis = { clock },
                delayMillis = { wait -> delays += wait; clock += wait },
            ).awaitSlot()
        }

        assertEquals(listOf(10L, PersistentRepostMasterLimiter.WINDOW_MILLIS - 10L), delays)
    }
}
