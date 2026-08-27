package com.rieltor.infrastructure.tiktok

import com.rieltor.domain.model.StoredTokens
import com.rieltor.domain.port.TikTokTokenRepository
import com.rieltor.infrastructure.config.ApplicationSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class TikTokAuthServiceTest {
    @Test
    fun `token request uses bare form content type`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals(
                ContentType.Application.FormUrlEncoded,
                request.body.contentType,
            )
            respond(
                content = """
                    {
                      "access_token":"access",
                      "refresh_token":"refresh",
                      "open_id":"open",
                      "expires_in":86400,
                      "refresh_expires_in":31536000
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val repository = InMemoryTokens()
        val service = TikTokAuthService(
            HttpClient(engine),
            ApplicationSettings(
                port = 8383,
                databasePath = Path.of("unused.db"),
                mediaDirectory = Path.of("media"),
                publicBaseUrl = "https://api.example",
                allowedTelegramSenderId = 530667295,
                telegramApiId = 12345,
                telegramApiHash = "telegram-api-hash",
                telegramSessionDirectory = Path.of("telegram-session"),
                tikTokClientKey = "client-key",
                tikTokClientSecret = "client-secret",
                tikTokRedirectUri = "https://api.example/auth/tiktok/callback",
            ),
            repository,
            Json { ignoreUnknownKeys = true },
        )

        service.exchangeCodeForTokens("authorization-code")
        assertEquals("open", repository.latest()?.openId)
    }

    private class InMemoryTokens : TikTokTokenRepository {
        private var tokens: StoredTokens? = null
        override fun save(tokens: StoredTokens) {
            this.tokens = tokens
        }
        override fun find(openId: String): StoredTokens? = tokens?.takeIf { it.openId == openId }
        override fun latest(): StoredTokens? = tokens
    }
}
