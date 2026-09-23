package com.rieltor.infrastructure.database.repository

import com.rieltor.domain.model.RepostDestination
import com.rieltor.domain.model.RepostStatus
import com.rieltor.domain.repository.TikTokRepository
import com.rieltor.domain.repository.TrackedTikTokPublish
import com.rieltor.infrastructure.database.local.RoomDatabaseStore

class TikTokRepositoryImpl(
    private val database: RoomDatabaseStore,
    private val catalog: CatalogRepository = CatalogRepository(database)
) : TikTokRepository {

    override fun reserveSlot(nowMillis: Long, windowMillis: Long, maxPostsPerWindow: Int, minIntervalMillis: Long) =
        database.settings.reserveAnonymous(nowMillis, windowMillis, maxPostsPerWindow, minIntervalMillis)

    override fun blockUntil(blockedUntilMillis: Long) =
        database.settings.update { it.copy(blockedUntil = maxOf(it.blockedUntil, blockedUntilMillis)) }

    override fun trackPublishForListing(
        listingId: Long,
        attemptId: String,
        publishId: String,
        mode: String,
        nowMillis: Long
    ) {
        catalog.changeAttempt(listingId, RepostDestination.TIKTOK, attemptId, nowMillis) {
            it.copy(status = RepostStatus.AwaitingConfirmation, publishId = publishId, mode = mode)
        }
    }

    override fun trackedPublishes(nowMillis: Long, retentionMillis: Long): List<TrackedTikTokPublish> =
        catalog.listings().flatMap { catalog.publication(it, RepostDestination.TIKTOK).attempts }
            .filter {
                it.publishId != null && it.status in setOf(
                    RepostStatus.AwaitingConfirmation,
                    RepostStatus.DeliveredDraft,
                    RepostStatus.Unknown,
                )
            }
            .map { TrackedTikTokPublish(requireNotNull(it.publishId), it.mode, it.createdAt, it.status.code) }

    override fun updateTrackedStatus(publishId: String, status: String, nowMillis: Long) {
        catalog.updatePublish(publishId, nowMillis) {
            it.copy(
                status = when (status) {
                    "PUBLISH_COMPLETE" -> RepostStatus.Published
                    "FAILED" -> RepostStatus.Failed
                    "SEND_TO_USER_INBOX" -> RepostStatus.DeliveredDraft
                    else -> RepostStatus.AwaitingConfirmation
                }
            )
        }
    }

    override fun removeTrackedPublish(publishId: String) =
        Unit // History belongs to the listing and is never erased here.
}
