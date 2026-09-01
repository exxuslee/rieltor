package com.rieltor.infrastructure.database.repository

import com.rieltor.domain.repository.TikTokPublishThrottleRepository
import com.rieltor.domain.repository.TrackedTikTokPublish
import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.model.TikTokTrackedPublishEntity

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

    override fun trackPublish(publishId: String, mode: String, nowMillis: Long) = database.blocking {
        it.tikTokThrottleDao().trackPublish(
            TikTokTrackedPublishEntity(publishId, mode, nowMillis, null, nowMillis)
        )
    }

    override fun trackedPublishes(nowMillis: Long, retentionMillis: Long): List<TrackedTikTokPublish> =
        database.blocking {
            it.tikTokThrottleDao().activeTrackedPublishes(nowMillis, retentionMillis).map { publish ->
                TrackedTikTokPublish(
                    publishId = publish.publishId,
                    mode = publish.mode,
                    createdAtMillis = publish.createdAt,
                    lastStatus = publish.lastStatus,
                )
            }
        }

    override fun updateTrackedStatus(publishId: String, status: String, nowMillis: Long) = database.blocking {
        it.tikTokThrottleDao().updateTrackedStatus(publishId, status, nowMillis)
    }

    override fun removeTrackedPublish(publishId: String) = database.blocking {
        it.tikTokThrottleDao().removeTrackedPublish(publishId)
    }
}
