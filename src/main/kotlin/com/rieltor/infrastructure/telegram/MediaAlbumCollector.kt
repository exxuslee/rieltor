package com.rieltor.infrastructure.telegram

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class MediaAlbumCollector<T>(
    private val scope: CoroutineScope,
    private val settleDelayMillis: Long,
    private val maxItemCount: Int,
    private val itemId: (T) -> Long,
    private val onReady: suspend (List<T>) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private val albums = mutableMapOf<Long, PendingAlbum<T>>()

    fun add(albumId: Long, item: T) {
        require(albumId != 0L) { "Album ID must not be zero." }
        synchronized(lock) {
            val album = albums.getOrPut(albumId) { PendingAlbum() }
            if (album.items.any { itemId(it) == itemId(item) }) return

            album.items += item
            album.generation++
            val generation = album.generation
            val flushDelay = if (album.items.size >= maxItemCount) 0L else settleDelayMillis
            album.flushJob?.cancel()
            album.flushJob = scope.launch {
                if (flushDelay > 0) delay(flushDelay)
                dispatchIfCurrent(albumId, generation)
            }
        }
    }

    private suspend fun dispatchIfCurrent(albumId: Long, generation: Long) {
        val items = synchronized(lock) {
            val album = albums[albumId]
            if (album == null || album.generation != generation) return
            albums.remove(albumId)
            album.items.toList()
        }
        onReady(items)
    }

    override fun close() {
        synchronized(lock) {
            albums.values.forEach { it.flushJob?.cancel() }
            albums.clear()
        }
    }

    private class PendingAlbum<T> {
        val items = mutableListOf<T>()
        var generation = 0L
        var flushJob: Job? = null
    }
}
