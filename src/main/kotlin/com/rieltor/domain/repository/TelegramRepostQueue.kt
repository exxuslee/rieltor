package com.rieltor.domain.repository

import com.rieltor.domain.model.TelegramListing

data class QueueEnqueueResult(val droppedUpdateId: Long? = null)

/** Persistent FIFO inbox between Telegram refresh and destination publishing. */
interface TelegramRepostQueue {
    fun recoverInterrupted()
    fun enqueue(listing: TelegramListing, capacity: Int): QueueEnqueueResult
    fun peekOldest(): TelegramListing?
    fun complete(updateId: Long, status: String)
    fun reject(listing: TelegramListing, status: String)
    fun markRetryPending(updateId: Long, reason: String)
    fun cleanHistoryBefore(cutoffEpochSeconds: Long): Int
}
