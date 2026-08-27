package com.rieltor.infrastructure.telegram

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaAlbumCollectorTest {
    @Test
    fun `groups album items into one dispatch`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val delivered = CompletableDeferred<List<AlbumItem>>()
        val collector = MediaAlbumCollector(
            scope = scope,
            settleDelayMillis = 50,
            maxItemCount = 10,
            itemId = AlbumItem::id,
            onReady = { delivered.complete(it) },
        )

        try {
            collector.add(77, AlbumItem(3))
            collector.add(77, AlbumItem(1))
            collector.add(77, AlbumItem(2))

            val album = withTimeout(1_000) { delivered.await() }
            assertEquals(listOf(3L, 1L, 2L), album.map { it.id })
        } finally {
            collector.close()
            scope.cancel()
        }
    }

    private data class AlbumItem(val id: Long)
}
