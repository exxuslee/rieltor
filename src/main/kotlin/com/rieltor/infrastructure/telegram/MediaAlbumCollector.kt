package com.rieltor.infrastructure.telegram

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory

/**
 * Telegram delivers an album (media group) as separate messages that share one media album id,
 * and only one of them carries the caption. Items are buffered until the album settles and are
 * then dispatched once, in arrival order, so a listing keeps every photo of its post.
 */
class MediaAlbumCollector<T : Any>(
    private val scope: CoroutineScope,
    private val settleDelayMillis: Long = DEFAULT_SETTLE_DELAY_MILLIS,
    private val maxItemCount: Int = DEFAULT_MAX_ITEM_COUNT,
    private val itemId: (T) -> Long,
    private val onReady: suspend (List<T>) -> Unit,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val albums = LinkedHashMap<Long, Album<T>>()

    private class Album<T : Any> {
        val items = LinkedHashMap<Long, T>()
        var job: Job? = null
        var token: Any = Any()
    }

    fun add(albumId: Long, item: T) {
        val full = synchronized(albums) {
            val album = albums.getOrPut(albumId) { Album() }
            album.items[itemId(item)] = item
            album.job?.cancel()
            album.job = null
            if (album.items.size >= maxItemCount) {
                albums.remove(albumId)
                album.items.values.toList()
            } else {
                val token = Any()
                album.token = token
                album.job = scope.launch {
                    delay(settleDelayMillis)
                    settle(albumId, token)
                }
                null
            }
        }
        full?.let { items -> scope.launch { deliver(albumId, items) } }
    }

    /** Dispatches every buffered album immediately; used before a batch import finishes. */
    suspend fun flush() {
        val pending = synchronized(albums) {
            val snapshot = albums.map { (id, album) -> album.job?.cancel(); id to album.items.values.toList() }
            albums.clear()
            snapshot
        }
        pending.forEach { (albumId, items) -> deliver(albumId, items) }
    }

    private suspend fun settle(albumId: Long, token: Any) {
        val items = synchronized(albums) {
            val album = albums[albumId] ?: return
            // A newer item restarted the timer: that dispatch owns the album.
            if (album.token !== token) return
            albums.remove(albumId)
            album.items.values.toList()
        }
        deliver(albumId, items)
    }

    private suspend fun deliver(albumId: Long, items: List<T>) {
        if (items.isEmpty()) return
        try {
            onReady(items)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logger.error("Telegram album dispatch failed. albumId={}, items={}", albumId, items.size, error)
        }
    }

    override fun close() {
        synchronized(albums) {
            albums.values.forEach { it.job?.cancel() }
            albums.clear()
        }
    }

    private companion object {
        const val DEFAULT_SETTLE_DELAY_MILLIS = 3_000L
        const val DEFAULT_MAX_ITEM_COUNT = 100
    }
}
