package com.rieltor.domain.repository

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
}
