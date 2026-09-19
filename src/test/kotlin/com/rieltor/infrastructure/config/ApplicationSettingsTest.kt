package com.rieltor.infrastructure.config

import com.rieltor.domain.model.TelegramMonitoredTopic
import com.rieltor.domain.repository.SecretRepository
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationSettingsTest {
    @Test
    fun `application settings are loaded from local json settings`() {
        val settings = ApplicationSettings.load(
            FakeSecrets(
                mapOf(
                    SecretNames.TIKTOK_REDIRECT_URI to "https://api.example/auth/tiktok/callback",
                    SecretNames.TIKTOK_CLIENT_KEY to "client-key",
                    SecretNames.TIKTOK_CLIENT_SECRET to "client-secret",
                    SecretNames.GOOGLE_CLIENT_ID to "google-client-id",
                    SecretNames.GOOGLE_CLIENT_SECRET to "google-client-secret",
                    SecretNames.GOOGLE_REDIRECT_URI to "https://api.example/auth/google/callback",
                    SecretNames.TELEGRAM_USER_ID to "999888777",
                    SecretNames.TELEGRAM_API_ID to "12345",
                    SecretNames.TELEGRAM_API_HASH to "api-hash",
                ),
            ),
            LocalSettings(
                mediaDirectory = "custom-media",
                tikTokMode = "draft",
                monitoredTelegramChats = listOf(
                    MonitoredTelegramChat(-1002681732909, listOf(5242880, 4194304)),
                    MonitoredTelegramChat(-1001234567890, listOf(50180)),
                ),
            ),
        )

        assertEquals(Path.of("tdlib-session-id999888777"), settings.telegramSessionDirectory)
        assertEquals(Path.of("custom-media"), settings.mediaDirectory)
        assertEquals(TikTokMode.DRAFT, settings.tikTokMode)
        assertEquals(
            setOf(
                TelegramMonitoredTopic(-1002681732909, 5242880),
                TelegramMonitoredTopic(-1002681732909, 4194304),
                TelegramMonitoredTopic(-1001234567890, 50180),
            ),
            settings.monitoredTelegramTopics,
        )
    }

    private class FakeSecrets(private val values: Map<String, String>) : SecretRepository {
        override fun get(name: String): String? = values[name]
        override fun putIfAbsent(name: String, value: String) = Unit
    }
}
