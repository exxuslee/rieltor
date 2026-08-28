package com.rieltor.infrastructure.config

import com.rieltor.domain.port.SecretRepository
import com.rieltor.domain.model.TelegramMonitoredTopic
import io.github.cdimascio.dotenv.Dotenv
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ApplicationSettingsTest {
    @Test
    fun `bootstrap does not persist tiktok application credentials`() {
        val directory = Files.createTempDirectory("rieltor-bootstrap-test")
        Files.writeString(
            directory.resolve(".env"),
            "TIKTOK_CLIENT_KEY=client-key\nTIKTOK_CLIENT_SECRET=client-secret\n"
        )
        val secrets = FakeSecrets(mutableMapOf())

        bootstrapSecrets(secrets, Dotenv.configure().directory(directory.toString()).load())

        assertNull(secrets.get(SecretNames.TIKTOK_CLIENT_KEY))
        assertNull(secrets.get(SecretNames.TIKTOK_CLIENT_SECRET))
    }

    @Test
    fun `telegram user id from dotenv overrides stored value`() {
        val directory = Files.createTempDirectory("rieltor-settings-test")
        Files.writeString(
            directory.resolve(".env"),
            """
            TIKTOK_CLIENT_KEY=env-client-key
            TIKTOK_CLIENT_SECRET=env-client-secret
            TELEGRAM_USER_ID=999888777
            """.trimIndent()
        )
        val dotenv = Dotenv.configure().directory(directory.toString()).load()
        val secrets = FakeSecrets(
            mutableMapOf(
                SecretNames.TIKTOK_REDIRECT_URI to "https://api.example/auth/tiktok/callback",
                SecretNames.TELEGRAM_API_ID to "12345",
                SecretNames.TELEGRAM_API_HASH to "api-hash",
                SecretNames.TELEGRAM_USER_ID to "530666333",
            )
        )

        val settings = ApplicationSettings.load(secrets, dotenv)

        assertEquals(
            Path.of("tdlib-session-id999888777"),
            settings.telegramSessionDirectory,
        )
        assertEquals("env-client-key", settings.tikTokClientKey)
        assertEquals("env-client-secret", settings.tikTokClientSecret)
    }

    @Test
    fun `telegram monitoring supports whole chats and individual forum topics`() {
        val directory = Files.createTempDirectory("rieltor-monitoring-test")
        Files.writeString(
            directory.resolve(".env"),
            """
            TIKTOK_CLIENT_KEY=client-key
            TIKTOK_CLIENT_SECRET=client-secret
            TELEGRAM_MONITORED_TOPICS=-1002681732909,-1001234567890_50180
            """.trimIndent()
        )
        val secrets = FakeSecrets(
            mutableMapOf(
                SecretNames.TIKTOK_REDIRECT_URI to "https://api.example/auth/tiktok/callback",
                SecretNames.TELEGRAM_API_ID to "12345",
                SecretNames.TELEGRAM_API_HASH to "api-hash",
                SecretNames.TELEGRAM_USER_ID to "530666333",
            )
        )

        val settings = ApplicationSettings.load(
            secrets,
            Dotenv.configure().directory(directory.toString()).load(),
        )

        assertEquals(
            setOf(
                TelegramMonitoredTopic(-1002681732909L),
                TelegramMonitoredTopic(-1001234567890L, 50180L),
            ),
            settings.monitoredTelegramTopics,
        )
    }

    @Test
    fun `telegram chat id and message thread ids are configured separately`() {
        val directory = Files.createTempDirectory("rieltor-separated-monitoring-test")
        Files.writeString(
            directory.resolve(".env"),
            """
            TIKTOK_CLIENT_KEY=client-key
            TIKTOK_CLIENT_SECRET=client-secret
            TELEGRAM_MONITORED_CHAT_ID=-1002681732909
            TELEGRAM_MONITORED_MESSAGE_THREAD_IDS=5242880,4194304
            """.trimIndent()
        )
        val secrets = FakeSecrets(
            mutableMapOf(
                SecretNames.TIKTOK_REDIRECT_URI to "https://api.example/auth/tiktok/callback",
                SecretNames.TELEGRAM_API_ID to "12345",
                SecretNames.TELEGRAM_API_HASH to "api-hash",
                SecretNames.TELEGRAM_USER_ID to "530666333",
            )
        )

        val settings = ApplicationSettings.load(
            secrets,
            Dotenv.configure().directory(directory.toString()).load(),
        )

        assertEquals(
            setOf(
                TelegramMonitoredTopic(-1002681732909L, 5242880L),
                TelegramMonitoredTopic(-1002681732909L, 4194304L),
            ),
            settings.monitoredTelegramTopics,
        )
    }

    private class FakeSecrets(private val values: MutableMap<String, String>) : SecretRepository {
        override fun get(name: String): String? = values[name]

        override fun putIfAbsent(name: String, value: String) {
            values.putIfAbsent(name, value)
        }
    }
}
