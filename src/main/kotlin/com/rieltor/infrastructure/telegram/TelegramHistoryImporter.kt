package com.rieltor.infrastructure.telegram

import com.rieltor.domain.model.TelegramMonitoredTopic
import it.tdlight.jni.TdApi
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.time.Instant

internal class TelegramHistoryImporter(
    private val topics: Set<TelegramMonitoredTopic>,
    private val page: suspend (Long, Long) -> List<TdApi.Message>,
    private val receive: (TdApi.Message) -> Unit,
) {
    suspend fun run(from: Instant, until: Instant): Long {
        require(from <= until)
        var accepted = 0L
        for (chatId in topics.map { it.chatId }.distinct()) {
            var cursor = 0L
            while (true) {
                val messages = page(chatId, cursor)
                if (messages.isEmpty()) break
                val older = messages.filter { cursor == 0L || it.id < cursor }
                check(older.isNotEmpty()) { "Telegram history cursor did not advance for chat $chatId at $cursor" }
                for (message in older.distinctBy { it.id }) {
                    val date = Instant.ofEpochSecond(message.date.toLong())
                    if (date >= from && date <= until && topics.any { it.matches(message.chatId, message.messageThreadId) }) {
                        receive(message)
                        accepted++
                    }
                }
                logger.info("History chat={}, accepted={}, oldest={}", chatId, accepted, older.minOf { it.date })
                if (older.any { Instant.ofEpochSecond(it.date.toLong()) < from }) break
                cursor = older.minOf { it.id }
                // Short pages are normal for TDLib; only an empty page ends the history.
                delay(200)
            }
        }
        return accepted
    }

    private companion object {
        val logger = LoggerFactory.getLogger(TelegramHistoryImporter::class.java)
    }
}
