package com.rieltor.infrastructure.threads

import com.rieltor.domain.model.RepostDestination
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

class ThreadsPhotoPublisherTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `creates image children carousel and publishes it`() = runBlocking {
        val createRequests = mutableListOf<Map<String, String>>()
        var createNumber = 0
        var publishCreationId: String? = null
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/me/threads") -> {
                    createRequests += request.url.parameters.entries().associate { it.key to it.value.single() }
                    createNumber++
                    jsonResponse("""{"id":"container-$createNumber"}""")
                }
                request.url.encodedPath.endsWith("/me/threads_publish") -> {
                    publishCreationId = request.url.parameters["creation_id"]
                    jsonResponse("""{"id":"thread-99"}""")
                }
                else -> jsonResponse("""{"id":"status","status":"FINISHED"}""")
            }
        }
        val client = HttpClient(engine)
        val repository = InMemoryThreadsTokens(
            StoredThreadsTokens("user-1", "access", Instant.now().epochSecond + 2_000_000)
        )
        val auth = ThreadsAuthService(client, settings(), repository, json)
        val publisher = ThreadsPhotoPublisher(client, auth, json, statusPollDelayMillis = 0)

        val receipt = publisher.publish(
            listOf("https://api.example/media/one.jpg", "https://api.example/media/two.jpg"),
            "Опис квартири",
        )

        assertEquals(RepostDestination.THREADS, receipt.destination)
        assertEquals("thread-99", receipt.publishId)
        assertEquals(3, createRequests.size)
        assertEquals(listOf("IMAGE", "IMAGE", "CAROUSEL"), createRequests.map { it["media_type"] })
        assertTrue(createRequests.take(2).all { it["is_carousel_item"] == "true" })
        assertEquals("container-1,container-2", createRequests.last()["children"])
        assertEquals("Опис квартири\n\nhttps://rieltor.dpdns.org/catalog", createRequests.last()["text"])
        assertEquals("container-3", publishCreationId)
    }

    @Test
    fun `single photo adds catalog link only when it fits within Threads text limit`() = runBlocking {
        var createdParameters = emptyMap<String, String>()
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/me/threads") -> {
                    createdParameters = request.url.parameters.entries().associate { it.key to it.value.single() }
                    jsonResponse("""{"id":"image-container"}""")
                }
                request.url.encodedPath.endsWith("/me/threads_publish") -> jsonResponse("""{"id":"thread-1"}""")
                else -> jsonResponse("""{"status":"FINISHED"}""")
            }
        }
        val client = HttpClient(engine)
        val auth = ThreadsAuthService(
            client,
            settings(),
            InMemoryThreadsTokens(StoredThreadsTokens("user-1", "access", Instant.now().epochSecond + 2_000_000)),
            json,
        )

        val publisher = ThreadsPhotoPublisher(client, auth, json, statusPollDelayMillis = 0)
        val link = "https://rieltor.dpdns.org/catalog"
        val fittingDescription = "а".repeat(500 - link.length - 2)
        val cases = listOf(
            null to link,
            fittingDescription to "$fittingDescription\n\n$link",
            (fittingDescription + "а") to (fittingDescription + "а"),
            "а".repeat(500) to "а".repeat(500),
            "а".repeat(700) to "а".repeat(500),
        )
        for ((caption, expectedText) in cases) {
            publisher.publish(listOf("https://api.example/media/one.jpg"), caption)

            assertEquals("IMAGE", createdParameters["media_type"])
            assertEquals(expectedText, createdParameters["text"], "Caption length: ${caption?.length}")
        }
    }

    @Test
    fun `API denial reports HTTP codes and trace without exposing token`() = runBlocking {
        val client = HttpClient(MockEngine {
            respond(
                """{"error":{"message":"API access blocked secret-token","type":"OAuthException","code":200,"error_subcode":123,"fbtrace_id":"trace-42","is_transient":false}}""",
                HttpStatusCode.Forbidden,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        })
        val auth = ThreadsAuthService(client, settings(), InMemoryThreadsTokens(
            StoredThreadsTokens("user-1", "secret-token", Instant.now().epochSecond + 2_000_000)
        ), json)
        try {
            val error = kotlin.test.assertFailsWith<ThreadsAuthException> {
                ThreadsPhotoPublisher(client, auth, json).publish(listOf("https://example.com/photo.jpg"), null)
            }
            val reason = error.message.orEmpty()
            listOf("HTTP=403", "code=200", "subcode=123", "traceId=trace-42", "OAuthException", "API access blocked").forEach {
                assertTrue(reason.contains(it), reason)
            }
            assertTrue(!reason.contains("secret-token"))
        } finally { client.close() }
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

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.jsonResponse(body: String) = respond(
        content = body,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    private class InMemoryThreadsTokens(private var value: StoredThreadsTokens) : ThreadsTokenRepository {
        override fun save(tokens: StoredThreadsTokens) { value = tokens }
        override fun load(): StoredThreadsTokens = value
    }
}
