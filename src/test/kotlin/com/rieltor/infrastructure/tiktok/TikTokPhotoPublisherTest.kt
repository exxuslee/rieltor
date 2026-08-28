package com.rieltor.infrastructure.tiktok

import com.rieltor.domain.model.StoredTokens
import com.rieltor.domain.port.TikTokTokenRepository
import com.rieltor.infrastructure.config.ApplicationSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import java.io.IOException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TikTokPhotoPublisherTest {
    @Test
    fun `queues concurrent posts and keeps safe interval between them`() = runBlocking {
        val json = Json { ignoreUnknownKeys = true }
        var clockMillis = 0L
        val publishRequestTimes = mutableListOf<Long>()
        var publishNumber = 0
        val engine = MockEngine { request ->
            if (request.url.encodedPath.contains("creator_info")) {
                respond(
                    content = """{"data":{"creator_nickname":"Ірина","privacy_level_options":["SELF_ONLY"]},"error":{"code":"ok","message":""}}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            } else {
                publishRequestTimes += clockMillis
                publishNumber++
                respond(
                    content = """{"data":{"publish_id":"publish-$publishNumber"},"error":{"code":"ok","message":""}}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
        }
        val httpClient = HttpClient(engine)
        val auth = TikTokAuthService(httpClient, settings(), validTokens(), json)
        val publisher = TikTokPhotoPublisher(
            httpClient = httpClient,
            auth = auth,
            json = json,
            minPublishIntervalMillis = 11_000,
            nowMillis = { clockMillis },
            delayMillis = { clockMillis += it },
        )

        listOf("first", "second", "third").map { name ->
            async { publisher.publish(listOf("https://api.example/media/$name.jpg"), name) }
        }.awaitAll()

        assertEquals(listOf(0L, 11_000L, 22_000L), publishRequestTimes)
    }

    @Test
    fun `retries rate limited post after cooldown`() = runBlocking {
        val json = Json { ignoreUnknownKeys = true }
        val delays = mutableListOf<Long>()
        var publishAttempts = 0
        val engine = MockEngine { request ->
            if (request.url.encodedPath.contains("creator_info")) {
                respond(
                    content = """{"data":{"creator_nickname":"Ірина","privacy_level_options":["SELF_ONLY"]},"error":{"code":"ok","message":""}}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            } else {
                publishAttempts++
                val responseBody = if (publishAttempts == 1) {
                    """{"data":{},"error":{"code":"rate_limit_exceeded","message":"Try later","log_id":"rate-log"}}"""
                } else {
                    """{"data":{"publish_id":"publish-after-wait"},"error":{"code":"ok","message":""}}"""
                }
                respond(
                    content = responseBody,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
        }
        val httpClient = HttpClient(engine)
        val auth = TikTokAuthService(httpClient, settings(), validTokens(), json)
        val publisher = TikTokPhotoPublisher(
            httpClient = httpClient,
            auth = auth,
            json = json,
            minPublishIntervalMillis = 0,
            rateLimitRetryDelayMillis = 60_000,
            nowMillis = { 0L },
            delayMillis = { delays += it },
        )

        val receipt = publisher.publish(listOf("https://api.example/media/photo.jpg"), null)

        assertEquals("publish-after-wait", receipt.publishId)
        assertEquals(2, publishAttempts)
        assertEquals(listOf(60_000L), delays)
    }

    @Test
    fun `retries temporary creator info network failure`() = runBlocking {
        val json = Json { ignoreUnknownKeys = true }
        var creatorAttempts = 0
        val engine = MockEngine { request ->
            if (request.url.encodedPath.contains("creator_info")) {
                creatorAttempts++
                if (creatorAttempts == 1) throw IOException("temporary timeout")
                respond(
                    content = """{"data":{"creator_nickname":"Ірина","privacy_level_options":["SELF_ONLY"]},"error":{"code":"ok","message":""}}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            } else {
                respond(
                    content = """{"data":{"publish_id":"publish-retry"},"error":{"code":"ok","message":""}}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
        }
        val httpClient = HttpClient(engine)
        val auth = TikTokAuthService(httpClient, settings(), validTokens(), json)
        val result = TikTokPhotoPublisher(httpClient, auth, json, retryDelayMillis = 0)
            .publish(listOf("https://api.example/media/photo.jpg"), null)

        assertEquals(2, creatorAttempts)
        assertTrue(result.publishId.isNotBlank())
    }

    @Test
    fun `sends all photo urls in one TikTok request`() = runBlocking {
        val json = Json { ignoreUnknownKeys = true }
        var publishBody: String? = null
        val engine = MockEngine { request ->
            if (request.url.encodedPath.contains("creator_info")) {
                respond(
                    content = """
                        {
                          "data": {
                            "creator_nickname": "Ірина",
                            "privacy_level_options": ["SELF_ONLY"]
                          },
                          "error": {"code": "ok", "message": ""}
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            } else {
                publishBody = (request.body as TextContent).text
                respond(
                    content = """
                        {
                          "data": {"publish_id": "publish-album"},
                          "error": {"code": "ok", "message": ""}
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
        }
        val httpClient = HttpClient(engine)
        val tokens = validTokens()
        val auth = TikTokAuthService(httpClient, settings(), tokens, json)
        val publisher = TikTokPhotoPublisher(httpClient, auth, json)
        val urls = listOf(
            "https://api.example/media/first.jpg",
            "https://api.example/media/second.jpg",
            "https://api.example/media/third.jpg",
        )

        publisher.publish(urls, "Альбом квартири")

        val photoImages = json.parseToJsonElement(requireNotNull(publishBody))
            .jsonObject.getValue("source_info")
            .jsonObject.getValue("photo_images")
            .jsonArray
            .map { it.toString().trim('"') }
        assertEquals(urls, photoImages)
    }

    private fun settings() = ApplicationSettings(
        port = 8383,
        databasePath = Path.of("unused.db"),
        mediaDirectory = Path.of("media"),
        publicBaseUrl = "https://api.example",
        telegramApiId = 12345,
        telegramApiHash = "telegram-api-hash",
        telegramSessionDirectory = Path.of("telegram-session"),
        tikTokClientKey = "client-key",
        tikTokClientSecret = "client-secret",
        tikTokRedirectUri = "https://api.example/auth/tiktok/callback",
    )

    private fun validTokens() = InMemoryTokens(
        StoredTokens(
            openId = "open",
            accessToken = "access",
            refreshToken = "refresh",
            accessTokenExpiresAt = Instant.now().epochSecond + 3_600,
            refreshTokenExpiresAt = Instant.now().epochSecond + 86_400,
        )
    )

    private class InMemoryTokens(private var tokens: StoredTokens) : TikTokTokenRepository {
        override fun save(tokens: StoredTokens) {
            this.tokens = tokens
        }

        override fun find(openId: String): StoredTokens? = tokens.takeIf { it.openId == openId }

        override fun latest(): StoredTokens = tokens
    }
}
