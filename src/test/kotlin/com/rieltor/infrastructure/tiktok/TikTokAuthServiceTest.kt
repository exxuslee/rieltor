package com.rieltor.infrastructure.tiktok

import com.rieltor.domain.model.StoredTokens
import com.rieltor.domain.repository.TikTokTokenRepository
import com.rieltor.infrastructure.config.ApplicationSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
        val service = service(engine, repository)

        service.exchangeCodeForTokens("authorization-code")
        assertEquals("open", repository.latest()?.openId)
    }

    @Test
    fun `unreadable token response becomes auth exception`() = runBlocking {
        val engine = MockEngine {
            respond(
                content = "upstream unavailable",
                status = HttpStatusCode.BadGateway,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
            )
        }

        val error = assertFailsWith<TikTokAuthException> {
            service(engine, InMemoryTokens()).exchangeCodeForTokens("authorization-code")
        }

        assertEquals(
            "TikTok token endpoint returned an unreadable response (HTTP 502).",
            error.message,
        )
    }

    private fun service(engine: MockEngine, repository: TikTokTokenRepository) = TikTokAuthService(
        HttpClient(engine),
        ApplicationSettings(
            mediaDirectory = Path.of("media"),
            publicBaseUrl = "https://api.example",
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

    private class InMemoryTokens : TikTokTokenRepository {
        private var tokens: StoredTokens? = null
        override fun save(tokens: StoredTokens) {
            this.tokens = tokens
        }
        override fun find(openId: String): StoredTokens? = tokens?.takeIf { it.openId == openId }
        override fun latest(): StoredTokens? = tokens
    }
}
