package com.rieltor.infrastructure.tiktok

import com.rieltor.domain.model.StoredTokens
import com.rieltor.domain.repository.TikTokPublishThrottleRepository
import com.rieltor.domain.repository.TikTokTokenRepository
import com.rieltor.infrastructure.config.ApplicationSettings
import com.rieltor.infrastructure.config.TikTokMode
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.IOException
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
            if (request.method == HttpMethod.Head) {
                publicPhoto()
            } else if (request.url.encodedPath.contains("status/fetch")) {
                completedStatus()
            } else if (request.url.encodedPath.contains("creator_info")) {
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
            if (request.method == HttpMethod.Head) {
                publicPhoto()
            } else if (request.url.encodedPath.contains("status/fetch")) {
                completedStatus()
            } else if (request.url.encodedPath.contains("creator_info")) {
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
    fun `daily post limit pauses persistent dispatcher`() = runBlocking {
        val json = Json { ignoreUnknownKeys = true }
        val throttle = CapturingThrottleRepository()
        val engine = MockEngine { request ->
            when {
                request.method == HttpMethod.Head -> publicPhoto()
                request.url.encodedPath.contains("creator_info") -> creatorInfo()
                else -> respond(
                    content = """{"data":{},"error":{"code":"spam_risk_too_many_posts","message":"Daily limit"}}""",
                    status = HttpStatusCode.Forbidden,
                    headers = jsonHeaders(),
                )
            }
        }
        val httpClient = HttpClient(engine)
        val publisher = TikTokPhotoPublisher(
            httpClient = httpClient,
            auth = TikTokAuthService(httpClient, settings(), validTokens(), json),
            json = json,
            dispatcher = TikTokPublishDispatcher(
                repository = throttle,
                maxPostsPer24Hours = 10,
                minPostIntervalMillis = 0,
                dailyLimitCooldownMillis = 24 * 60 * 60 * 1_000L,
                nowMillis = { 1_000L },
            ),
        )

        val error = kotlin.test.assertFailsWith<TikTokAuthException> {
            publisher.publish(listOf("https://api.example/media/photo.jpg"), null)
        }

        assertTrue(error.message.orEmpty().contains("spam_risk_too_many_posts"))
        assertEquals(24 * 60 * 60 * 1_000L + 1_000L, throttle.blockedUntil)
    }

    @Test
    fun `retries temporary creator info network failure`() = runBlocking {
        val json = Json { ignoreUnknownKeys = true }
        var creatorAttempts = 0
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Head) {
                publicPhoto()
            } else if (request.url.encodedPath.contains("status/fetch")) {
                completedStatus()
            } else if (request.url.encodedPath.contains("creator_info")) {
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
            if (request.method == HttpMethod.Head) {
                publicPhoto()
            } else if (request.url.encodedPath.contains("status/fetch")) {
                completedStatus()
            } else if (request.url.encodedPath.contains("creator_info")) {
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

    @Test
    fun `waits until TikTok confirms completed publication`() = runBlocking {
        val json = Json { ignoreUnknownKeys = true }
        var statusChecks = 0
        val delays = mutableListOf<Long>()
        val engine = MockEngine { request ->
            when {
                request.method == HttpMethod.Head -> publicPhoto()
                request.url.encodedPath.contains("creator_info") -> creatorInfo()
                request.url.encodedPath.contains("status/fetch") -> {
                    statusChecks++
                    if (statusChecks == 1) processingStatus() else completedStatus()
                }
                else -> publishAccepted("publish-confirmed")
            }
        }
        val httpClient = HttpClient(engine)
        val publisher = TikTokPhotoPublisher(
            httpClient,
            TikTokAuthService(httpClient, settings(), validTokens(), json),
            json,
            statusPollIntervalMillis = 5_000,
            delayMillis = { delays += it },
        )

        val receipt = publisher.publish(listOf("https://api.example/media/photo.jpg"), "Квартира")

        assertEquals("publish-confirmed", receipt.publishId)
        assertEquals(2, statusChecks)
        assertEquals(listOf(5_000L), delays)
    }

    @Test
    fun `draft mode uploads media for manual editing and stops after inbox delivery`() = runBlocking {
        val json = Json { ignoreUnknownKeys = true }
        var publishBody: String? = null
        var statusChecks = 0
        val engine = MockEngine { request ->
            when {
                request.method == HttpMethod.Head -> publicPhoto()
                request.url.encodedPath.contains("creator_info") -> creatorInfo()
                request.url.encodedPath.contains("status/fetch") -> {
                    statusChecks++
                    respond(
                        content = """{"data":{"status":"SEND_TO_USER_INBOX"},"error":{"code":"ok","message":""}}""",
                        status = HttpStatusCode.OK,
                        headers = jsonHeaders(),
                    )
                }
                else -> {
                    publishBody = (request.body as TextContent).text
                    publishAccepted("draft-accepted")
                }
            }
        }
        val httpClient = HttpClient(engine)
        val publisher = TikTokPhotoPublisher(
            httpClient,
            TikTokAuthService(httpClient, settings(), validTokens(), json),
            json,
            tikTokMode = TikTokMode.DRAFT,
        )

        val receipt = publisher.publish(listOf("https://api.example/media/photo.jpg"), "Чернетка")

        val body = json.parseToJsonElement(requireNotNull(publishBody)).jsonObject
        val postInfo = body.getValue("post_info").jsonObject
        assertEquals("\"MEDIA_UPLOAD\"", body.getValue("post_mode").toString())
        assertEquals(false, "privacy_level" in postInfo)
        assertEquals(false, "auto_add_music" in postInfo)
        assertEquals("DRAFT", receipt.privacyLevel)
        assertEquals(1, statusChecks)
    }

    @Test
    fun `status polling of one post does not block initialization of another`() = runBlocking {
        val json = Json { ignoreUnknownKeys = true }
        val firstStatusStarted = CompletableDeferred<Unit>()
        val releaseFirstStatus = CompletableDeferred<Unit>()
        val secondInitializationStarted = CompletableDeferred<Unit>()
        var publishNumber = 0
        var statusNumber = 0
        val engine = MockEngine { request ->
            when {
                request.method == HttpMethod.Head -> publicPhoto()
                request.url.encodedPath.contains("creator_info") -> creatorInfo()
                request.url.encodedPath.contains("status/fetch") -> {
                    statusNumber++
                    if (statusNumber == 1) {
                        firstStatusStarted.complete(Unit)
                        releaseFirstStatus.await()
                    }
                    completedStatus()
                }
                else -> {
                    publishNumber++
                    if (publishNumber == 2) secondInitializationStarted.complete(Unit)
                    publishAccepted("publish-$publishNumber")
                }
            }
        }
        val httpClient = HttpClient(engine)
        val publisher = TikTokPhotoPublisher(
            httpClient,
            TikTokAuthService(httpClient, settings(), validTokens(), json),
            json,
            minPublishIntervalMillis = 0,
        )

        val first = async { publisher.publish(listOf("https://api.example/media/first.jpg"), "Перший") }
        withTimeout(1_000) { firstStatusStarted.await() }
        val second = async { publisher.publish(listOf("https://api.example/media/second.jpg"), "Другий") }

        withTimeout(1_000) { secondInitializationStarted.await() }
        releaseFirstStatus.complete(Unit)
        val receipts = awaitAll(first, second)

        assertEquals(setOf("publish-1", "publish-2"), receipts.map { it.publishId }.toSet())
    }

    @Test
    fun `reports TikTok failure returned after initialization`() = runBlocking {
        val json = Json { ignoreUnknownKeys = true }
        val engine = MockEngine { request ->
            when {
                request.method == HttpMethod.Head -> publicPhoto()
                request.url.encodedPath.contains("creator_info") -> creatorInfo()
                request.url.encodedPath.contains("status/fetch") -> respond(
                    content = """{"data":{"status":"FAILED","fail_reason":"photo_pull_failed"},"error":{"code":"ok","message":"","log_id":"status-log"}}""",
                    status = HttpStatusCode.OK,
                    headers = jsonHeaders(),
                )
                else -> publishAccepted("publish-failed")
            }
        }
        val httpClient = HttpClient(engine)
        val publisher = TikTokPhotoPublisher(
            httpClient,
            TikTokAuthService(httpClient, settings(), validTokens(), json),
            json,
        )

        val error = kotlin.test.assertFailsWith<TikTokAuthException> {
            publisher.publish(listOf("https://api.example/media/photo.jpg"), null)
        }

        assertTrue(error.message.orEmpty().contains("photo_pull_failed"))
        assertTrue(error.message.orEmpty().contains("publish-failed"))
    }

    @Test
    fun `rejects inaccessible public photo before TikTok initialization`() = runBlocking {
        val json = Json { ignoreUnknownKeys = true }
        var tikTokRequests = 0
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Head) {
                respond(content = "", status = HttpStatusCode.Forbidden)
            } else {
                tikTokRequests++
                creatorInfo()
            }
        }
        val httpClient = HttpClient(engine)
        val publisher = TikTokPhotoPublisher(
            httpClient,
            TikTokAuthService(httpClient, settings(), validTokens(), json),
            json,
        )

        val error = kotlin.test.assertFailsWith<TikTokAuthException> {
            publisher.publish(listOf("https://api.example/media/forbidden.jpg"), null)
        }

        assertTrue(error.message.orEmpty().contains("HTTP 403"))
        assertEquals(0, tikTokRequests)
    }

    private fun MockRequestHandleScope.creatorInfo() = respond(
        content = """{"data":{"creator_nickname":"Ірина","privacy_level_options":["SELF_ONLY"]},"error":{"code":"ok","message":"","log_id":"creator-log"}}""",
        status = HttpStatusCode.OK,
        headers = jsonHeaders(),
    )

    private fun MockRequestHandleScope.publishAccepted(publishId: String) = respond(
        content = """{"data":{"publish_id":"$publishId"},"error":{"code":"ok","message":"","log_id":"publish-log"}}""",
        status = HttpStatusCode.OK,
        headers = jsonHeaders(),
    )

    private fun MockRequestHandleScope.processingStatus() = respond(
        content = """{"data":{"status":"PROCESSING_DOWNLOAD","downloaded_bytes":1024},"error":{"code":"ok","message":"","log_id":"status-log-1"}}""",
        status = HttpStatusCode.OK,
        headers = jsonHeaders(),
    )

    private fun MockRequestHandleScope.completedStatus() = respond(
        content = """{"data":{"status":"PUBLISH_COMPLETE"},"error":{"code":"ok","message":"","log_id":"status-log-2"}}""",
        status = HttpStatusCode.OK,
        headers = jsonHeaders(),
    )

    private fun MockRequestHandleScope.publicPhoto() = respond(
        content = "",
        status = HttpStatusCode.OK,
        headers = headersOf(
            HttpHeaders.ContentType to listOf(ContentType.Image.JPEG.toString()),
            HttpHeaders.ContentLength to listOf("2257000"),
        ),
    )

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun settings() = ApplicationSettings(
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

    private class CapturingThrottleRepository : TikTokPublishThrottleRepository {
        var blockedUntil: Long? = null

        override fun reserveSlot(
            nowMillis: Long,
            windowMillis: Long,
            maxPostsPerWindow: Int,
            minIntervalMillis: Long,
        ): Long = 0

        override fun blockUntil(blockedUntilMillis: Long) {
            blockedUntil = blockedUntilMillis
        }
    }
}
