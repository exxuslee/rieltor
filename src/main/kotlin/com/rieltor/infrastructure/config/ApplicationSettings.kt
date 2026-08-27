package com.rieltor.infrastructure.config

import com.rieltor.domain.port.SecretRepository
import io.github.cdimascio.dotenv.Dotenv
import java.net.URI
import java.nio.file.Path

object SecretNames {
    const val TIKTOK_CLIENT_KEY = "TIKTOK_CLIENT_KEY"
    const val TIKTOK_CLIENT_SECRET = "TIKTOK_CLIENT_SECRET"
    const val TIKTOK_REDIRECT_URI = "TIKTOK_REDIRECT_URI"
    const val TELEGRAM_BOT_TOKEN = "TELEGRAM_BOT_TOKEN"
    const val TELEGRAM_API_ID = "TELEGRAM_API_ID"
    const val TELEGRAM_API_HASH = "TELEGRAM_API_HASH"
    const val TELEGRAM_USER_ID = "TELEGRAM_USER_ID"
    const val PUBLIC_BASE_URL = "PUBLIC_BASE_URL"

    val bootstrapNames = listOf(
        TIKTOK_CLIENT_KEY,
        TIKTOK_CLIENT_SECRET,
        TIKTOK_REDIRECT_URI,
        TELEGRAM_BOT_TOKEN,
        TELEGRAM_API_ID,
        TELEGRAM_API_HASH,
        TELEGRAM_USER_ID,
        PUBLIC_BASE_URL,
    )
}

data class ApplicationSettings(
    val port: Int,
    val databasePath: Path,
    val mediaDirectory: Path,
    val publicBaseUrl: String,
    val allowedTelegramSenderId: Long,
    val telegramBotToken: String,
    val tikTokClientKey: String,
    val tikTokClientSecret: String,
    val tikTokRedirectUri: String,
) {
    companion object {
        fun load(secrets: SecretRepository, dotenv: Dotenv): ApplicationSettings {
            val redirectUri = secrets.require(SecretNames.TIKTOK_REDIRECT_URI)
            val redirect = URI(redirectUri)
            val inferredBaseUrl = "${redirect.scheme}://${redirect.authority}"
            return ApplicationSettings(
                port = environmentOrDotenv("PORT", dotenv)?.toIntOrNull() ?: 8383,
                databasePath = Path.of(environmentOrDotenv("APP_DB_PATH", dotenv) ?: "data/rieltor.db"),
                mediaDirectory = Path.of(environmentOrDotenv("MEDIA_DIRECTORY", dotenv) ?: "data/media"),
                publicBaseUrl = secrets.get(SecretNames.PUBLIC_BASE_URL)?.trimEnd('/') ?: inferredBaseUrl,
                allowedTelegramSenderId = 530667295L,
                telegramBotToken = secrets.require(SecretNames.TELEGRAM_BOT_TOKEN),
                tikTokClientKey = secrets.require(SecretNames.TIKTOK_CLIENT_KEY),
                tikTokClientSecret = secrets.require(SecretNames.TIKTOK_CLIENT_SECRET),
                tikTokRedirectUri = redirectUri,
            )
        }
    }
}

fun bootstrapSecrets(repository: SecretRepository, dotenv: Dotenv) {
    SecretNames.bootstrapNames.forEach { name ->
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
