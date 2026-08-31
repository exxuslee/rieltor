package com.rieltor.infrastructure.config

import com.rieltor.domain.model.TelegramMonitoredTopic
import com.rieltor.domain.repository.SecretRepository
import io.github.cdimascio.dotenv.Dotenv
import java.net.URI
import java.nio.file.Path

object SecretNames {
    const val TIKTOK_CLIENT_KEY = "TIKTOK_CLIENT_KEY"
    const val TIKTOK_CLIENT_SECRET = "TIKTOK_CLIENT_SECRET"
    const val TIKTOK_REDIRECT_URI = "TIKTOK_REDIRECT_URI"
    const val GOOGLE_CLIENT_ID = "GOOGLE_CLIENT_ID"
    const val GOOGLE_CLIENT_SECRET = "GOOGLE_CLIENT_SECRET"
    const val GOOGLE_REDIRECT_URI = "GOOGLE_REDIRECT_URI"
    const val THREADS_APP_ID = "THREADS_APP_ID"
    const val THREADS_APP_SECRET = "THREADS_APP_SECRET"
    const val THREADS_REDIRECT_URI = "THREADS_REDIRECT_URI"
    const val TELEGRAM_API_ID = "TELEGRAM_API_ID"
    const val TELEGRAM_API_HASH = "TELEGRAM_API_HASH"
    const val TELEGRAM_USER_ID = "TELEGRAM_USER_ID"
    const val LANDING_TELEGRAM_BOT_TOKEN = "LANDING_TELEGRAM_BOT_TOKEN"
    const val LANDING_TELEGRAM_CHAT_ID = "LANDING_TELEGRAM_CHAT_ID"
    const val PUBLIC_BASE_URL = "PUBLIC_BASE_URL"

    val sqliteNames = listOf(
        TIKTOK_REDIRECT_URI,
        GOOGLE_REDIRECT_URI,
        THREADS_REDIRECT_URI,
        TELEGRAM_API_ID,
        TELEGRAM_API_HASH,
        PUBLIC_BASE_URL,
    )
}

data class ApplicationSettings(
    val mediaDirectory: Path,
    val publicBaseUrl: String,
    val monitoredTelegramTopics: Set<TelegramMonitoredTopic> = emptySet(),
    val telegramApiId: Int,
    val telegramApiHash: String,
    val telegramSessionDirectory: Path,
    val tikTokClientKey: String,
    val tikTokClientSecret: String,
    val tikTokRedirectUri: String,
    val tikTokMode: TikTokMode = TikTokMode.POST,
    val tikTokMaxPostsPer24Hours: Int = DEFAULT_TIKTOK_MAX_POSTS_PER_24_HOURS,
    val tikTokMinPostIntervalMinutes: Long = DEFAULT_TIKTOK_MIN_POST_INTERVAL_MINUTES,
    val tikTokDailyLimitCooldownHours: Long = DEFAULT_TIKTOK_DAILY_LIMIT_COOLDOWN_HOURS,
    val repostMaxPhotoCount: Int = DEFAULT_REPOST_MAX_PHOTO_COUNT,
    val googleClientId: String = "",
    val googleClientSecret: String = "",
    val googleRedirectUri: String = "",
    val threadsAppId: String = "",
    val threadsAppSecret: String = "",
    val threadsRedirectUri: String = "",
    val landingTelegramBotToken: String = "",
    val landingTelegramChatId: String = "",
) {
    val threadsConfigured: Boolean
        get() = threadsAppId.isNotBlank() && threadsAppSecret.isNotBlank() && threadsRedirectUri.isNotBlank()

    val landingTelegramConfigured: Boolean
        get() = landingTelegramBotToken.isNotBlank() && landingTelegramChatId.isNotBlank()

    companion object {
        fun load(secrets: SecretRepository, dotenv: Dotenv): ApplicationSettings {
            val redirectUri = secrets.require(SecretNames.TIKTOK_REDIRECT_URI)
            val redirect = URI(redirectUri)
            val inferredBaseUrl = "${redirect.scheme}://${redirect.authority}"
            val telegramUserId = requireEnvironmentOrDotenv(SecretNames.TELEGRAM_USER_ID, dotenv)
                .toLongOrNull()
                ?: error("Invalid ${SecretNames.TELEGRAM_USER_ID}: expected a numeric Telegram user ID")
            return ApplicationSettings(
                mediaDirectory = Path.of(environmentOrDotenv("MEDIA_DIRECTORY", dotenv) ?: "media"),
                publicBaseUrl = secrets.get(SecretNames.PUBLIC_BASE_URL)?.trimEnd('/') ?: inferredBaseUrl,
                monitoredTelegramTopics = parseTelegramMonitoredTopics(
                    chatIdValue = environmentOrDotenv("TELEGRAM_MONITORED_CHAT_ID", dotenv),
                    messageThreadIdsValue = environmentOrDotenv(
                        "TELEGRAM_MONITORED_MESSAGE_THREAD_IDS",
                        dotenv,
                    ),
                    legacyValue = environmentOrDotenv("TELEGRAM_MONITORED_TOPICS", dotenv),
                ),
                telegramApiId = secrets.require(SecretNames.TELEGRAM_API_ID).toInt(),
                telegramApiHash = secrets.require(SecretNames.TELEGRAM_API_HASH),
                telegramSessionDirectory = Path.of("tdlib-session-id$telegramUserId"),
                tikTokClientKey = requireEnvironmentOrDotenv(SecretNames.TIKTOK_CLIENT_KEY, dotenv),
                tikTokClientSecret = requireEnvironmentOrDotenv(SecretNames.TIKTOK_CLIENT_SECRET, dotenv),
                tikTokRedirectUri = redirectUri,
                tikTokMode = tikTokMode(dotenv),
                tikTokMaxPostsPer24Hours = tikTokMaxPostsPer24Hours(dotenv),
                tikTokMinPostIntervalMinutes = tikTokMinPostIntervalMinutes(dotenv),
                tikTokDailyLimitCooldownHours = tikTokDailyLimitCooldownHours(dotenv),
                repostMaxPhotoCount = repostMaxPhotoCount(dotenv),
                googleClientId = requireEnvironmentOrDotenv(SecretNames.GOOGLE_CLIENT_ID, dotenv),
                googleClientSecret = requireEnvironmentOrDotenv(SecretNames.GOOGLE_CLIENT_SECRET, dotenv),
                googleRedirectUri = secrets.require(SecretNames.GOOGLE_REDIRECT_URI),
                threadsAppId = environmentOrDotenv(SecretNames.THREADS_APP_ID, dotenv).orEmpty(),
                threadsAppSecret = environmentOrDotenv(SecretNames.THREADS_APP_SECRET, dotenv).orEmpty(),
                threadsRedirectUri = secrets.get(SecretNames.THREADS_REDIRECT_URI).orEmpty(),
                landingTelegramBotToken = environmentOrDotenv(SecretNames.LANDING_TELEGRAM_BOT_TOKEN, dotenv).orEmpty(),
                landingTelegramChatId = environmentOrDotenv(SecretNames.LANDING_TELEGRAM_CHAT_ID, dotenv).orEmpty(),
            )
        }
    }
}

enum class TikTokMode {
    POST,
    DRAFT,
}

fun databasePath(dotenv: Dotenv): Path =
    Path.of(environmentOrDotenv("APP_DB_PATH", dotenv) ?: "rieltor.db")

fun serverPort(dotenv: Dotenv): Int {
    val configured = environmentOrDotenv("PORT", dotenv) ?: return DEFAULT_SERVER_PORT
    return configured.toIntOrNull()?.takeIf { it in 1..65535 }
        ?: error("Invalid PORT: expected a number from 1 to 65535")
}

fun repostMaxPhotoCount(dotenv: Dotenv): Int {
    val configured = environmentOrDotenv("REPOST_MAX_PHOTO_COUNT", dotenv)
        ?: return DEFAULT_REPOST_MAX_PHOTO_COUNT
    return configured.toIntOrNull()?.takeIf { it in 1..TIKTOK_API_MAX_PHOTO_COUNT }
        ?: error(
            "Invalid REPOST_MAX_PHOTO_COUNT: expected a number from 1 to $TIKTOK_API_MAX_PHOTO_COUNT"
        )
}

fun tikTokMode(dotenv: Dotenv): TikTokMode {
    val configured = environmentOrDotenv("TIKTOK_MODE", dotenv) ?: return TikTokMode.POST
    return runCatching { TikTokMode.valueOf(configured.trim().uppercase()) }
        .getOrElse { error("Invalid TIKTOK_MODE: expected POST or DRAFT") }
}

fun tikTokMaxPostsPer24Hours(dotenv: Dotenv): Int =
    positiveInt(dotenv, "TIKTOK_MAX_POSTS_PER_24_HOURS", DEFAULT_TIKTOK_MAX_POSTS_PER_24_HOURS, 1..100)

fun tikTokMinPostIntervalMinutes(dotenv: Dotenv): Long =
    positiveLong(dotenv, "TIKTOK_MIN_POST_INTERVAL_MINUTES", DEFAULT_TIKTOK_MIN_POST_INTERVAL_MINUTES, 1L..1_440L)

fun tikTokDailyLimitCooldownHours(dotenv: Dotenv): Long =
    positiveLong(dotenv, "TIKTOK_DAILY_LIMIT_COOLDOWN_HOURS", DEFAULT_TIKTOK_DAILY_LIMIT_COOLDOWN_HOURS, 1L..48L)

private fun positiveInt(dotenv: Dotenv, name: String, default: Int, range: IntRange): Int {
    val configured = environmentOrDotenv(name, dotenv) ?: return default
    return configured.toIntOrNull()?.takeIf { it in range }
        ?: error("Invalid $name: expected a number from ${range.first} to ${range.last}")
}

private fun positiveLong(dotenv: Dotenv, name: String, default: Long, range: LongRange): Long {
    val configured = environmentOrDotenv(name, dotenv) ?: return default
    return configured.toLongOrNull()?.takeIf { it in range }
        ?: error("Invalid $name: expected a number from ${range.first} to ${range.last}")
}

fun bootstrapSecrets(repository: SecretRepository, dotenv: Dotenv) {
    SecretNames.sqliteNames.forEach { name ->
        environmentOrDotenv(name, dotenv)?.let { repository.putIfAbsent(name, it) }
    }
}

private fun SecretRepository.require(name: String): String =
    get(name) ?: error(
        "Missing '$name' in the local SQLite database. " +
            "Provide it once as an environment/.env value to bootstrap the database."
    )

private fun environmentOrDotenv(name: String, dotenv: Dotenv): String? =
    (System.getenv(name) ?: dotenv.get(name))?.takeIf { it.isNotBlank() }

private fun requireEnvironmentOrDotenv(name: String, dotenv: Dotenv): String =
    environmentOrDotenv(name, dotenv)
        ?: error("Missing '$name' in the process environment or .env file.")

private fun parseTelegramMonitoredTopics(
    chatIdValue: String?,
    messageThreadIdsValue: String?,
    legacyValue: String?,
): Set<TelegramMonitoredTopic> {
    if (chatIdValue != null || messageThreadIdsValue != null) {
        val chatId = chatIdValue?.toLongOrNull()
            ?: error("TELEGRAM_MONITORED_CHAT_ID must contain a numeric Telegram chat ID")
        val messageThreadIds = messageThreadIdsValue
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.map { value ->
                value.toLongOrNull()
                    ?: error("Invalid messageThreadId '$value' in TELEGRAM_MONITORED_MESSAGE_THREAD_IDS")
            }
            ?.toSet()
            .orEmpty()
        return if (messageThreadIds.isEmpty()) {
            setOf(TelegramMonitoredTopic(chatId))
        } else {
            messageThreadIds.mapTo(linkedSetOf()) { messageThreadId ->
                TelegramMonitoredTopic(chatId, messageThreadId)
            }
        }
    }

    return legacyValue
        ?.split(',')
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.map { value ->
            val parts = value.split('_', limit = 2)
            val chatId = parts[0].toLongOrNull()
                ?: error("Invalid Telegram chat ID '${parts[0]}' in TELEGRAM_MONITORED_TOPICS")
            val messageThreadId = parts.getOrNull(1)?.toLongOrNull()
            require(parts.size == 1 || messageThreadId != null) {
                "Invalid TELEGRAM_MONITORED_TOPICS value '$value': expected <chatId> or <chatId>_<messageThreadId>"
            }
            TelegramMonitoredTopic(chatId, messageThreadId)
        }
        ?.toSet()
        ?: emptySet()
}

private const val DEFAULT_SERVER_PORT = 8383
const val DEFAULT_REPOST_MAX_PHOTO_COUNT = 10
const val TIKTOK_API_MAX_PHOTO_COUNT = 35
const val DEFAULT_TIKTOK_MAX_POSTS_PER_24_HOURS = 10
const val DEFAULT_TIKTOK_MIN_POST_INTERVAL_MINUTES = 120L
const val DEFAULT_TIKTOK_DAILY_LIMIT_COOLDOWN_HOURS = 24L
