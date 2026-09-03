package com.rieltor.infrastructure.threads

import com.rieltor.domain.model.StoredThreadsTokens
import com.rieltor.domain.repository.ThreadsTokenRepository
import com.rieltor.infrastructure.config.ApplicationSettings
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThreadsAuthServiceTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `builds authorize url and exchanges short token for long lived token`() = runBlocking {
        val paths = mutableListOf<String>()
        val engine = MockEngine { request ->
            paths += request.url.encodedPath
            val content = if (request.url.encodedPath.endsWith("/oauth/access_token")) {
                """{"user_id":17841400000000000,"access_token":"short","expires_in":3600}"""
            } else {
                """{"access_token":"long","expires_in":5184000}"""
            }
            respond(
                content,
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val repository = InMemoryThreadsTokens()
        val service = ThreadsAuthService(HttpClient(engine), settings(), repository, json)

        val authorizeUrl = io.ktor.http.Url(service.buildAuthorizeUrl("state-1"))
        val stored = service.exchangeCodeForTokens("code-1")

        assertEquals("threads-app", authorizeUrl.parameters["client_id"])
        assertEquals("threads_basic,threads_content_publish", authorizeUrl.parameters["scope"])
        assertEquals("state-1", authorizeUrl.parameters["state"])
        assertEquals(listOf("/oauth/access_token", "/access_token"), paths)
        assertEquals("17841400000000000", stored.userId)
        assertEquals("long", stored.accessToken)
        assertEquals(stored, repository.load())
    }

    @Test
    fun `refreshes a long lived token close to expiration`() = runBlocking {
        var requestedPath: String? = null
        val engine = MockEngine { request ->
            requestedPath = request.url.encodedPath
            assertEquals("th_refresh_token", request.url.parameters["grant_type"])
            respond(
                """{"access_token":"refreshed","expires_in":5184000}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val repository = InMemoryThreadsTokens(
            StoredThreadsTokens("threads-user", "old", Instant.now().epochSecond + 3600)
        )
        val service = ThreadsAuthService(HttpClient(engine), settings(), repository, json)

        assertEquals("refreshed", service.validAccessToken())
        assertTrue(requireNotNull(requestedPath).endsWith("/refresh_access_token"))
        assertEquals("threads-user", repository.load()?.userId)
    }

    private fun settings() = ApplicationSettings(
        mediaDirectory = Path.of("media"),
        publicBaseUrl = "https://api.example",
        telegramApiId = 12345,
        telegramApiHash = "hash",
        telegramSessionDirectory = Path.of("session"),
        tikTokClientKey = "tiktok-key",
        tikTokClientSecret = "tiktok-secret",
        tikTokRedirectUri = "https://api.example/auth/tiktok/callback",
        threadsAppId = "threads-app",
        threadsAppSecret = "threads-secret",
        threadsRedirectUri = "https://api.example/auth/threads/callback",
    )

    private class InMemoryThreadsTokens(private var value: StoredThreadsTokens? = null) : ThreadsTokenRepository {
        override fun save(tokens: StoredThreadsTokens) { value = tokens }
        override fun load(): StoredThreadsTokens? = value
    }
}
