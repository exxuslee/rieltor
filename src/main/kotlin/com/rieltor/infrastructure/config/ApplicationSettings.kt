package com.rieltor.infrastructure.config

import com.rieltor.domain.model.TelegramMonitoredTopic
import com.rieltor.domain.repository.SecretRepository
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
    const val TELEGRAM_LISTING_BOT_TOKEN = "TELEGRAM_LISTING_BOT_TOKEN"
    const val LANDING_TELEGRAM_BOT_TOKEN = "LANDING_TELEGRAM_BOT_TOKEN"
    const val LANDING_TELEGRAM_CHAT_ID = "LANDING_TELEGRAM_CHAT_ID"
    const val PUBLIC_BASE_URL = "PUBLIC_BASE_URL"

    val fileNames = listOf(
        TIKTOK_CLIENT_KEY,
        TIKTOK_CLIENT_SECRET,
        TIKTOK_REDIRECT_URI,
        GOOGLE_CLIENT_ID,
        GOOGLE_CLIENT_SECRET,
        GOOGLE_REDIRECT_URI,
        THREADS_APP_ID,
        THREADS_APP_SECRET,
        THREADS_REDIRECT_URI,
        TELEGRAM_API_ID,
        TELEGRAM_API_HASH,
        TELEGRAM_USER_ID,
        TELEGRAM_LISTING_BOT_TOKEN,
        LANDING_TELEGRAM_BOT_TOKEN,
        LANDING_TELEGRAM_CHAT_ID,
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
    val repostMaxMessagesPer24Hours: Int = DEFAULT_REPOST_MAX_MESSAGES_PER_24_HOURS,
    val repostMinIntervalMinutes: Long = DEFAULT_REPOST_MIN_INTERVAL_MINUTES,
    val tikTokDailyLimitCooldownHours: Long = DEFAULT_TIKTOK_DAILY_LIMIT_COOLDOWN_HOURS,
    val repostMaxPhotoCount: Int = DEFAULT_REPOST_MAX_PHOTO_COUNT,
    val googleClientId: String = "",
    val googleClientSecret: String = "",
    val googleRedirectUri: String = "",
    val threadsAppId: String = "",
    val threadsAppSecret: String = "",
    val threadsRedirectUri: String = "",
    val threadsEnabled: Boolean = false,
    val telegramListingBotToken: String = "",
    val telegramListingBotMaxPhotoCount: Int = DEFAULT_TELEGRAM_LISTING_BOT_MAX_PHOTO_COUNT,
    val landingTelegramBotToken: String = "",
    val landingTelegramChatId: String = "",
) {
    val threadsConfigured: Boolean
        get() = threadsAppId.isNotBlank() && threadsAppSecret.isNotBlank() && threadsRedirectUri.isNotBlank()

    val landingTelegramConfigured: Boolean
        get() = landingTelegramBotToken.isNotBlank() && landingTelegramChatId.isNotBlank()

    companion object {
        fun load(secrets: SecretRepository, local: LocalSettings): ApplicationSettings {
            val redirectUri = secrets.require(SecretNames.TIKTOK_REDIRECT_URI)
            val redirect = URI(redirectUri)
            val inferredBaseUrl = "${redirect.scheme}://${redirect.authority}"
            val telegramUserId = secrets.require(SecretNames.TELEGRAM_USER_ID)
                .toLongOrNull()
                ?: error("Invalid ${SecretNames.TELEGRAM_USER_ID}: expected a numeric Telegram user ID")
            val landingBotToken = secrets.get(SecretNames.LANDING_TELEGRAM_BOT_TOKEN).orEmpty()
            return ApplicationSettings(
                mediaDirectory = Path.of(local.mediaDirectory),
                publicBaseUrl = secrets.get(SecretNames.PUBLIC_BASE_URL)?.trimEnd('/') ?: inferredBaseUrl,
                monitoredTelegramTopics = local.monitoredTelegramChats.flatMapTo(linkedSetOf()) { chat ->
                    if (chat.messageThreadIds.isEmpty()) listOf(TelegramMonitoredTopic(chat.chatId))
                    else chat.messageThreadIds.map { TelegramMonitoredTopic(chat.chatId, it) }
                },
                telegramApiId = secrets.require(SecretNames.TELEGRAM_API_ID).toInt(),
                telegramApiHash = secrets.require(SecretNames.TELEGRAM_API_HASH),
                telegramSessionDirectory = Path.of("tdlib-session-id$telegramUserId"),
                tikTokClientKey = secrets.require(SecretNames.TIKTOK_CLIENT_KEY),
                tikTokClientSecret = secrets.require(SecretNames.TIKTOK_CLIENT_SECRET),
                tikTokRedirectUri = redirectUri,
                tikTokMode = TikTokMode.valueOf(local.tikTokMode.uppercase()),
                repostMaxMessagesPer24Hours = local.maxMessagesPer24Hours,
                repostMinIntervalMinutes = local.minIntervalMs / 60_000,
                tikTokDailyLimitCooldownHours = local.tikTokDailyLimitCooldownHours,
                repostMaxPhotoCount = local.repostMaxPhotoCount,
                googleClientId = secrets.require(SecretNames.GOOGLE_CLIENT_ID),
                googleClientSecret = secrets.require(SecretNames.GOOGLE_CLIENT_SECRET),
                googleRedirectUri = secrets.require(SecretNames.GOOGLE_REDIRECT_URI),
                threadsAppId = secrets.get(SecretNames.THREADS_APP_ID).orEmpty(),
                threadsAppSecret = secrets.get(SecretNames.THREADS_APP_SECRET).orEmpty(),
                threadsRedirectUri = secrets.get(SecretNames.THREADS_REDIRECT_URI).orEmpty(),
                threadsEnabled = local.threadsEnabled,
                telegramListingBotToken = secrets.get(SecretNames.TELEGRAM_LISTING_BOT_TOKEN)
                    .orEmpty()
                    .ifBlank { landingBotToken },
                telegramListingBotMaxPhotoCount = local.telegramListingBotMaxPhotoCount,
                landingTelegramBotToken = landingBotToken,
                landingTelegramChatId = secrets.get(SecretNames.LANDING_TELEGRAM_CHAT_ID).orEmpty(),
            )
        }
    }
}

enum class TikTokMode {
    POST,
    DRAFT,
}

fun databasePath(settings: LocalSettings): Path = Path.of(settings.databasePath)

fun credentialsPath(settings: LocalSettings): Path = Path.of(settings.secretsPath)

private fun SecretRepository.require(name: String): String =
    get(name) ?: error(
        "Missing '$name' in the local secrets JSON file."
    )

const val DEFAULT_REPOST_MAX_PHOTO_COUNT = 10
const val TIKTOK_API_MAX_PHOTO_COUNT = 35
const val DEFAULT_REPOST_MAX_MESSAGES_PER_24_HOURS = 36
const val DEFAULT_REPOST_MIN_INTERVAL_MINUTES = 20L
const val DEFAULT_TIKTOK_DAILY_LIMIT_COOLDOWN_HOURS = 8L
const val DEFAULT_TELEGRAM_LISTING_BOT_MAX_PHOTO_COUNT = 100
