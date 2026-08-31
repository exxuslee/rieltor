package com.rieltor.infrastructure.config

import com.rieltor.domain.model.TelegramMonitoredTopic
import com.rieltor.domain.repository.SecretRepository
import io.github.cdimascio.dotenv.Dotenv
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ApplicationSettingsTest {
    @Test
    fun `server port is loaded once from dotenv`() {
        val directory = Files.createTempDirectory("rieltor-port-test")
        Files.writeString(directory.resolve(".env"), "PORT=9484")

        val port = serverPort(Dotenv.configure().directory(directory.toString()).load())

        assertEquals(9484, port)
    }

    @Test
    fun `tiktok photo count defaults to a safe value for a small VM`() {
        val directory = Files.createTempDirectory("rieltor-tiktok-limit-default-test")

        val limit = repostMaxPhotoCount(Dotenv.configure().directory(directory.toString()).ignoreIfMissing().load())

        assertEquals(10, limit)
    }

    @Test
    fun `tiktok photo count is configurable within api limits`() {
        val directory = Files.createTempDirectory("rieltor-tiktok-limit-test")
        Files.writeString(directory.resolve(".env"), "REPOST_MAX_PHOTO_COUNT=12")

        val limit = repostMaxPhotoCount(Dotenv.configure().directory(directory.toString()).load())

        assertEquals(12, limit)
    }

    @Test
    fun `tiktok photo count rejects unsafe values`() {
        val directory = Files.createTempDirectory("rieltor-tiktok-limit-invalid-test")
        Files.writeString(directory.resolve(".env"), "REPOST_MAX_PHOTO_COUNT=36")

        assertFailsWith<IllegalStateException> {
            repostMaxPhotoCount(Dotenv.configure().directory(directory.toString()).load())
        }
    }

    @Test
    fun `tiktok mode defaults to post and accepts draft case insensitively`() {
        val defaultDirectory = Files.createTempDirectory("rieltor-repost-mode-default-test")
        val draftDirectory = Files.createTempDirectory("rieltor-repost-mode-draft-test")
        Files.writeString(draftDirectory.resolve(".env"), "TIKTOK_MODE=draft")

        assertEquals(
            TikTokMode.POST,
            tikTokMode(Dotenv.configure().directory(defaultDirectory.toString()).ignoreIfMissing().load()),
        )
        assertEquals(
            TikTokMode.DRAFT,
            tikTokMode(Dotenv.configure().directory(draftDirectory.toString()).load()),
        )
    }

    @Test
    fun `tiktok mode rejects unknown values`() {
        val directory = Files.createTempDirectory("rieltor-repost-mode-invalid-test")
        Files.writeString(directory.resolve(".env"), "TIKTOK_MODE=LATER")

        assertFailsWith<IllegalStateException> {
            tikTokMode(Dotenv.configure().directory(directory.toString()).load())
        }
    }

    @Test
    fun `bootstrap persists neither oauth credentials nor telegram user id`() {
        val directory = Files.createTempDirectory("rieltor-bootstrap-test")
        Files.writeString(
            directory.resolve(".env"),
            """
            TIKTOK_CLIENT_KEY=client-key
            TIKTOK_CLIENT_SECRET=client-secret
            GOOGLE_CLIENT_ID=google-client-id
            GOOGLE_CLIENT_SECRET=google-client-secret
            TELEGRAM_USER_ID=530666333
            """.trimIndent()
        )
        val secrets = FakeSecrets(mutableMapOf())

        bootstrapSecrets(secrets, Dotenv.configure().directory(directory.toString()).load())

        assertNull(secrets.get(SecretNames.TIKTOK_CLIENT_KEY))
        assertNull(secrets.get(SecretNames.TIKTOK_CLIENT_SECRET))
        assertNull(secrets.get(SecretNames.GOOGLE_CLIENT_ID))
        assertNull(secrets.get(SecretNames.GOOGLE_CLIENT_SECRET))
        assertNull(secrets.get(SecretNames.TELEGRAM_USER_ID))
    }

    @Test
    fun `telegram user id is read from dotenv`() {
        val directory = Files.createTempDirectory("rieltor-settings-test")
        Files.writeString(
            directory.resolve(".env"),
            """
            TIKTOK_CLIENT_KEY=env-client-key
            TIKTOK_CLIENT_SECRET=env-client-secret
            GOOGLE_CLIENT_ID=google-client-id
            GOOGLE_CLIENT_SECRET=google-client-secret
            TELEGRAM_USER_ID=999888777
            """.trimIndent()
        )
        val dotenv = Dotenv.configure().directory(directory.toString()).load()
        val secrets = FakeSecrets(
            mutableMapOf(
                SecretNames.TIKTOK_REDIRECT_URI to "https://api.example/auth/tiktok/callback",
                SecretNames.GOOGLE_REDIRECT_URI to "https://api.example/auth/google/callback",
                SecretNames.TELEGRAM_API_ID to "12345",
                SecretNames.TELEGRAM_API_HASH to "api-hash",
            )
        )

        val settings = ApplicationSettings.load(secrets, dotenv)

        assertEquals(
            Path.of("tdlib-session-id999888777"),
            settings.telegramSessionDirectory,
        )
        assertEquals("env-client-key", settings.tikTokClientKey)
        assertEquals("env-client-secret", settings.tikTokClientSecret)
        assertEquals("google-client-id", settings.googleClientId)
        assertEquals("google-client-secret", settings.googleClientSecret)
        assertEquals("https://api.example/auth/google/callback", settings.googleRedirectUri)
    }

    @Test
    fun `telegram monitoring supports whole chats and individual forum topics`() {
        val directory = Files.createTempDirectory("rieltor-monitoring-test")
        Files.writeString(
            directory.resolve(".env"),
            """
            TIKTOK_CLIENT_KEY=client-key
            TIKTOK_CLIENT_SECRET=client-secret
            GOOGLE_CLIENT_ID=google-client-id
            GOOGLE_CLIENT_SECRET=google-client-secret
            TELEGRAM_USER_ID=530666333
            TELEGRAM_MONITORED_TOPICS=-1002681732909,-1001234567890_50180
            """.trimIndent()
        )
        val secrets = FakeSecrets(
            mutableMapOf(
                SecretNames.TIKTOK_REDIRECT_URI to "https://api.example/auth/tiktok/callback",
                SecretNames.GOOGLE_REDIRECT_URI to "https://api.example/auth/google/callback",
                SecretNames.TELEGRAM_API_ID to "12345",
                SecretNames.TELEGRAM_API_HASH to "api-hash",
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
            GOOGLE_CLIENT_ID=google-client-id
            GOOGLE_CLIENT_SECRET=google-client-secret
            TELEGRAM_USER_ID=530666333
            TELEGRAM_MONITORED_CHAT_ID=-1002681732909
            TELEGRAM_MONITORED_MESSAGE_THREAD_IDS=5242880,4194304
            """.trimIndent()
        )
        val secrets = FakeSecrets(
            mutableMapOf(
                SecretNames.TIKTOK_REDIRECT_URI to "https://api.example/auth/tiktok/callback",
                SecretNames.GOOGLE_REDIRECT_URI to "https://api.example/auth/google/callback",
                SecretNames.TELEGRAM_API_ID to "12345",
                SecretNames.TELEGRAM_API_HASH to "api-hash",
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
