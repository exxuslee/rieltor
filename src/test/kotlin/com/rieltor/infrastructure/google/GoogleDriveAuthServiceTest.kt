package com.rieltor.infrastructure.google

import com.rieltor.domain.model.StoredGoogleDriveTokens
import com.rieltor.domain.port.GoogleDriveTokenRepository
import com.rieltor.infrastructure.config.ApplicationSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoogleDriveAuthServiceTest {
    @Test
    fun `authorization requests offline read only access and account hint`() {
        val service = service(HttpClient(MockEngine { error("No HTTP request expected") }), InMemoryTokens())

        val url = io.ktor.http.Url(service.buildAuthorizeUrl("state-123"))

        assertEquals("https://www.googleapis.com/auth/drive.readonly", url.parameters["scope"])
        assertEquals("offline", url.parameters["access_type"])
        assertEquals("consent", url.parameters["prompt"])
        assertEquals("agent@example.com", url.parameters["login_hint"])
        assertEquals("state-123", url.parameters["state"])
    }

    @Test
    fun `authorization code stores access and refresh tokens`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("oauth2.googleapis.com", request.url.host)
            assertTrue((request.body as TextContent).text.contains("grant_type=authorization_code"))
            respond(
                content = """{"access_token":"access","expires_in":3600,"refresh_token":"refresh"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val repository = InMemoryTokens()

        service(HttpClient(engine), repository).exchangeCodeForTokens("code")

        assertEquals("access", repository.load()?.accessToken)
        assertEquals("refresh", repository.load()?.refreshToken)
    }

    @Test
    fun `expired access token is refreshed while preserving refresh token`() = runBlocking {
        val repository = InMemoryTokens(
            StoredGoogleDriveTokens("expired", "persistent-refresh", Instant.now().epochSecond - 1)
        )
        val engine = MockEngine {
            respond(
                content = """{"access_token":"fresh","expires_in":3600}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val accessToken = service(HttpClient(engine), repository).validAccessToken()

        assertEquals("fresh", accessToken)
        assertEquals("persistent-refresh", repository.load()?.refreshToken)
    }

    private fun service(client: HttpClient, repository: GoogleDriveTokenRepository) = GoogleDriveAuthService(
        client,
        ApplicationSettings(
            port = 8383,
            databasePath = java.nio.file.Path.of("unused.db"),
            mediaDirectory = java.nio.file.Path.of("media"),
            publicBaseUrl = "https://api.example",
            telegramApiId = 1,
            telegramApiHash = "hash",
            telegramSessionDirectory = java.nio.file.Path.of("telegram"),
            tikTokClientKey = "tiktok-key",
            tikTokClientSecret = "tiktok-secret",
            tikTokRedirectUri = "https://api.example/auth/tiktok/callback",
            googleClientId = "google-client-id",
            googleClientSecret = "google-client-secret",
            googleRedirectUri = "https://api.example/auth/google/callback",
            googleAccountHint = "agent@example.com",
        ),
        repository,
        Json { ignoreUnknownKeys = true },
    )

    private class InMemoryTokens(initial: StoredGoogleDriveTokens? = null) : GoogleDriveTokenRepository {
        private var tokens = initial
        override fun save(tokens: StoredGoogleDriveTokens) {
            this.tokens = tokens
        }
        override fun load(): StoredGoogleDriveTokens? = tokens
    }
}
