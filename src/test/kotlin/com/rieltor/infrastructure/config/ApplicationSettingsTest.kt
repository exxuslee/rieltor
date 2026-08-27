package com.rieltor.infrastructure.config

import com.rieltor.domain.port.SecretRepository
import io.github.cdimascio.dotenv.Dotenv
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationSettingsTest {
    @Test
    fun `telegram user id from dotenv overrides stored value`() {
        val directory = Files.createTempDirectory("rieltor-settings-test")
        Files.writeString(directory.resolve(".env"), "TELEGRAM_USER_ID=999888777\n")
        val dotenv = Dotenv.configure().directory(directory.toString()).load()
        val secrets = FakeSecrets(
            mutableMapOf(
                SecretNames.TIKTOK_CLIENT_KEY to "client-key",
                SecretNames.TIKTOK_CLIENT_SECRET to "client-secret",
                SecretNames.TIKTOK_REDIRECT_URI to "https://api.example/auth/tiktok/callback",
                SecretNames.TELEGRAM_API_ID to "12345",
                SecretNames.TELEGRAM_API_HASH to "api-hash",
                SecretNames.TELEGRAM_USER_ID to "530667295",
            )
        )

        val settings = ApplicationSettings.load(secrets, dotenv)

        assertEquals(
            Path.of("data/telegram/tdlib-session-id999888777"),
            settings.telegramSessionDirectory,
        )
    }

    private class FakeSecrets(private val values: MutableMap<String, String>) : SecretRepository {
        override fun get(name: String): String? = values[name]

        override fun put(name: String, value: String) {
            values[name] = value
        }

        override fun putIfAbsent(name: String, value: String) {
            values.putIfAbsent(name, value)
        }
    }
}
