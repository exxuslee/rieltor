package com.rieltor.domain.repository

data class TrackedTikTokPublish(
    val publishId: String,
    val mode: String,
    val createdAtMillis: Long,
    val lastStatus: String?,
)

/** Persistent state used to coordinate TikTok publishing across coroutines and restarts. */
interface TikTokPublishThrottleRepository {
    /** Atomically reserves an attempt, or returns milliseconds until a slot is available. */
    fun reserveSlot(
        nowMillis: Long,
        windowMillis: Long,
        maxPostsPerWindow: Int,
        minIntervalMillis: Long,
    ): Long

    fun blockUntil(blockedUntilMillis: Long)

    fun trackPublish(publishId: String, mode: String, nowMillis: Long)

    fun trackedPublishes(nowMillis: Long, retentionMillis: Long): List<TrackedTikTokPublish>

    fun updateTrackedStatus(publishId: String, status: String, nowMillis: Long)

    fun removeTrackedPublish(publishId: String)
}
