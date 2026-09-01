package com.rieltor.infrastructure.database.repository

import com.rieltor.domain.repository.TikTokPublishThrottleRepository
import com.rieltor.infrastructure.database.local.RoomDatabaseStore

class TikTokPublishThrottleRepositoryImpl(
    private val database: RoomDatabaseStore,
) : TikTokPublishThrottleRepository {
    override fun reserveSlot(
        nowMillis: Long,
        windowMillis: Long,
        maxPostsPerWindow: Int,
        minIntervalMillis: Long,
    ): Long = database.blocking {
        it.tikTokThrottleDao().reserveSlot(nowMillis, windowMillis, maxPostsPerWindow, minIntervalMillis)
    }

    override fun blockUntil(blockedUntilMillis: Long) = database.blocking {
        it.tikTokThrottleDao().blockUntil(blockedUntilMillis)
    }
}