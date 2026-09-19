package com.rieltor.tools

import com.rieltor.infrastructure.config.*
import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.repository.CatalogRepository
import com.rieltor.infrastructure.telegram.TelegramClientAdapter
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** One-shot inbox backfill. Does not start the web server or processing/publication workers. */
fun main() = runBlocking {
    val root = Path.of(System.getenv("APP_PROJECT_ROOT") ?: ".").toAbsolutePath().normalize()
    JsonSettingsStore(root.resolve("settings.json")).use { settings ->
        val local = settings.snapshot()
        val secrets = JsonCredentialStore(credentialsPath(local))
        fun secret(name: String) = secrets.get(name)?.takeIf { it.isNotBlank() }
            ?: error("Missing $name in secrets.json")
        val apiId = secret(SecretNames.TELEGRAM_API_ID).toInt()
        val apiHash = secret(SecretNames.TELEGRAM_API_HASH)
        val userId = secret(SecretNames.TELEGRAM_USER_ID).toLong()
        val topics = (local.monitoredTelegramChats.flatMap { chat ->
            if (chat.messageThreadIds.isEmpty()) listOf(com.rieltor.domain.model.TelegramMonitoredTopic(chat.chatId))
            else chat.messageThreadIds.map { com.rieltor.domain.model.TelegramMonitoredTopic(chat.chatId, it) }
        } + local.topicTypeMapping.keys.map { key ->
            val parts = key.split(':')
            com.rieltor.domain.model.TelegramMonitoredTopic(parts[0].toLong(), parts[1].toLong())
        }).toSet()
        require(topics.isNotEmpty()) { "No monitored Telegram topics configured" }
        val until = ZonedDateTime.now(ZoneOffset.UTC)
        val from = until.minusMonths(1).toInstant()
        val logger = LoggerFactory.getLogger("TelegramHistoryMain")
        logger.info("Importing Telegram history from {} through {}", from, until.toInstant())
        RoomDatabaseStore(databasePath(local), settings, ownsSettings = false).use { database ->
            TelegramClientAdapter(
                apiId, apiHash, Path.of("tdlib-session-id$userId"), topics,
                CatalogRepository(database), settings, historyOnly = true
            ).use { telegram ->
                telegram.start()
                val count = telegram.importHistory(from, until.toInstant())
                logger.info(
                    "History import completed: {} messages passed to inbox (including existing). Start the main application to process them.",
                    count
                )
            }
        }
    }
}
