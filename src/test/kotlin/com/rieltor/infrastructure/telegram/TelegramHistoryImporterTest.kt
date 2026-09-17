package com.rieltor.infrastructure.telegram

import com.rieltor.domain.model.TelegramMonitoredTopic
import it.tdlight.jni.TdApi
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TelegramHistoryImporterTest {
    private fun message(id: Long, date: Int, topic: Long = 10) = TdApi.Message().also {
        it.id = id; it.date = date; it.chatId = -100; it.messageThreadId = topic
    }

    @Test fun `paginates short overlapping pages and filters topics and inclusive dates`() = runBlocking {
        val cursors = mutableListOf<Long>()
        val received = mutableListOf<Long>()
        val importer = TelegramHistoryImporter(setOf(TelegramMonitoredTopic(-100, 10)), { _, cursor ->
            cursors += cursor
            when (cursor) {
                0L -> listOf(message(9, 201), message(8, 200))
                8L -> listOf(message(8, 200), message(7, 150, 20), message(6, 100))
                6L -> listOf(message(6, 100), message(5, 99))
                else -> error("Unexpected cursor $cursor")
            }
        }, { received += it.id })
        assertEquals(2, importer.run(Instant.ofEpochSecond(100), Instant.ofEpochSecond(200)))
        assertEquals(listOf(8L, 6L), received)
        assertEquals(listOf(0L, 8L, 6L), cursors)
    }

    @Test fun `whole chat and multiple monitored topics scan chat only once until empty page`() = runBlocking {
        val calls = mutableListOf<Pair<Long, Long>>()
        val importer = TelegramHistoryImporter(setOf(TelegramMonitoredTopic(-100), TelegramMonitoredTopic(-100, 10)), { chat, cursor ->
            calls += chat to cursor
            if (cursor == 0L) listOf(message(3, 150, 30)) else emptyList()
        }, {})
        assertEquals(1, importer.run(Instant.ofEpochSecond(100), Instant.ofEpochSecond(200)))
        assertEquals(listOf(-100L to 0L, -100L to 3L), calls)
    }

    @Test fun `stalled cursor fails instead of silently truncating history`() = runBlocking {
        val importer = TelegramHistoryImporter(setOf(TelegramMonitoredTopic(-100)), { _, _ ->
            listOf(message(3, 150))
        }, {})
        assertFailsWith<IllegalStateException> {
            importer.run(Instant.ofEpochSecond(100), Instant.ofEpochSecond(200))
        }
        Unit
    }

    @Test fun `request failure is propagated for resumable rerun`() = runBlocking {
        val importer = TelegramHistoryImporter(setOf(TelegramMonitoredTopic(-100)), { _, _ ->
            error("Telegram unavailable")
        }, {})
        assertFailsWith<IllegalStateException> {
            importer.run(Instant.ofEpochSecond(100), Instant.ofEpochSecond(200))
        }
        Unit
    }
}
