package com.rieltor.infrastructure.tiktok

import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.repository.TikTokPublishThrottleRepositoryImpl
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class TikTokPublishDispatcherTest {
    @Test
    fun `spaces posts and enforces rolling window using persisted attempts`() = runBlocking {
        val repository = repository()
        var clock = 0L
        val delays = mutableListOf<Long>()
        val dispatcher = TikTokPublishDispatcher(
            repository = repository,
            maxPostsPer24Hours = 2,
            minPostIntervalMillis = 10,
            dailyLimitCooldownMillis = TikTokPublishDispatcher.WINDOW_MILLIS,
            nowMillis = { clock },
            delayMillis = { wait -> delays += wait; clock += wait },
        )

        dispatcher.awaitSlot()
        dispatcher.awaitSlot()
        dispatcher.awaitSlot()

        assertEquals(
            listOf(10L, TikTokPublishDispatcher.WINDOW_MILLIS - 10L),
            delays,
        )
    }

    @Test
    fun `daily limit cooldown survives a new dispatcher instance`() = runBlocking {
        val repository = repository()
        var clock = 1_000L
        val cooldown = 24 * 60 * 60 * 1_000L
        TikTokPublishDispatcher(
            repository, 10, 0, cooldown, { clock }, { clock += it },
        ).registerDailyLimit()

        val delays = mutableListOf<Long>()
        val restartedDispatcher = TikTokPublishDispatcher(
            repository, 10, 0, cooldown, { clock }, { wait -> delays += wait; clock += wait },
        )
        restartedDispatcher.awaitSlot()

        assertEquals(listOf(cooldown), delays)
    }

    private fun repository(): TikTokPublishThrottleRepositoryImpl {
        val path = Files.createTempDirectory("tiktok-dispatcher-test").resolve("rieltor.db")
        return TikTokPublishThrottleRepositoryImpl(RoomDatabaseStore(path))
    }
}
