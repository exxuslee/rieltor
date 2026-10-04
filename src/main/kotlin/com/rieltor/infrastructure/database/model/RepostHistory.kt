package com.rieltor.infrastructure.database.model

import com.rieltor.domain.model.PublicationState
import com.rieltor.domain.model.RepostStatus
import kotlinx.serialization.json.Json

private val historyJson = Json { ignoreUnknownKeys = true }
private val deliveredStatuses = setOf(RepostStatus.Published, RepostStatus.DeliveredDraft)

private fun attempts(state: String) = historyJson.decodeFromString<PublicationState>(state).attempts

/** An accepted publish ID is evidence of an external send, even while processing is pending. */
internal fun RepostEntity.hasExternalSend(): Boolean =
    tiktokRepostedAt != null || threadsRepostedAt != null ||
        listOf(tiktokStatus, threadsStatus).any { code -> deliveredStatuses.any { it.code == code } } ||
        listOf(tiktokState, threadsState).any { state ->
            attempts(state).any { it.publishId != null || it.status in deliveredStatuses }
        }

/** Use historical confirmation times; never invent a delivery date from migration time. */
internal fun RepostEntity.recoverDeliveryTimes(): RepostEntity = copy(
    tiktokRepostedAt = tiktokRepostedAt ?: attempts(tiktokState)
        .filter { it.status in deliveredStatuses }.minOfOrNull { it.updatedAt },
    threadsRepostedAt = threadsRepostedAt ?: attempts(threadsState)
        .filter { it.status in deliveredStatuses }.minOfOrNull { it.updatedAt },
)
