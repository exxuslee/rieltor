package com.rieltor.domain.repository

import com.rieltor.domain.model.TelegramListing

data class QueueEnqueueResult(val droppedUpdateId: Long? = null)

/** Lightweight queue view used for operational diagnostics without loading listing content. */
data class TelegramRepostQueueSnapshot(
    val claimedUpdateId: Long?,
    val pendingUpdateIds: List<Long>,
) {
    val size: Int get() = pendingUpdateIds.size + if (claimedUpdateId == null) 0 else 1
}

/** Persistent FIFO inbox between Telegram refresh and destination publishing. */
interface TelegramRepostQueue {
    fun recoverInterrupted()
    fun enqueue(listing: TelegramListing, capacity: Int): QueueEnqueueResult
    fun peekOldest(): TelegramListing?
    fun snapshot(): TelegramRepostQueueSnapshot
    fun complete(updateId: Long, status: String)
    fun reject(listing: TelegramListing, status: String)
    fun markRetryPending(updateId: Long, reason: String)
    fun cleanHistoryBefore(cutoffEpochSeconds: Long): Int
}
