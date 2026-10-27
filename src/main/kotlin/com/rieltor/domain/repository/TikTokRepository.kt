package com.rieltor.domain.repository

data class TrackedTikTokPublish(
    val publishId: String,
    val mode: String,
    val createdAtMillis: Long,
    val lastStatus: String?,
)

/** Persistent state used to coordinate TikTok publishing across coroutines and restarts. */
interface TikTokRepository {
    fun blockUntil(blockedUntilMillis: Long)

    fun trackPublishForListing(listingId: Long, attemptId: String, publishId: String, mode: String, nowMillis: Long)

    fun trackedPublishes(nowMillis: Long, retentionMillis: Long): List<TrackedTikTokPublish>

    fun updateTrackedStatus(publishId: String, status: String, nowMillis: Long)

}
