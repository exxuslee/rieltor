package com.rieltor.tools

import com.rieltor.domain.model.TelegramMonitoredTopic
import com.rieltor.infrastructure.config.*
import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.repository.CatalogRepository
import com.rieltor.infrastructure.telegram.TelegramClientAdapter
import io.github.cdimascio.dotenv.Dotenv
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** One-shot inbox backfill. Does not start the web server or processing/publication workers. */
fun main() = runBlocking {
    val dotenv = Dotenv.configure().ignoreIfMissing().load()
    fun env(name: String) = (System.getenv(name) ?: dotenv.get(name))?.takeIf { it.isNotBlank() }
    val secrets = JsonCredentialStore(credentialsPath(dotenv))
    fun secret(name: String) = secrets.get(name)?.takeIf { it.isNotBlank() } ?: env(name)
    ?: error("Missing $name in secrets.json or environment/.env")

    val apiId = secret(SecretNames.TELEGRAM_API_ID).toInt()
    val apiHash = secret(SecretNames.TELEGRAM_API_HASH)
    val userId = secret(SecretNames.TELEGRAM_USER_ID).toLong()
    val root = Path.of(System.getenv("APP_PROJECT_ROOT") ?: ".").toAbsolutePath().normalize()
    JsonSettingsStore(root.resolve("settings.json")).use { settings ->
        val topics = parseTelegramMonitoredTopics(
            env("TELEGRAM_MONITORED_CHAT_ID"), env("TELEGRAM_MONITORED_MESSAGE_THREAD_IDS"),
            env("TELEGRAM_MONITORED_TOPICS"),
        ) + settings.snapshot().topicTypeMapping.keys.map { key ->
            val parts = key.split(':')
            TelegramMonitoredTopic(parts[0].toLong(), parts[1].toLong())
        }
        require(topics.isNotEmpty()) { "No monitored Telegram topics configured" }
        val until = ZonedDateTime.now(ZoneOffset.UTC)
        val from = until.minusMonths(1).toInstant()
        val logger = LoggerFactory.getLogger("TelegramHistoryMain")
        logger.info("Importing Telegram history from {} through {}", from, until.toInstant())
        RoomDatabaseStore(databasePath(dotenv), settings, ownsSettings = false).use { database ->
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
