package com.rieltor.infrastructure.threads

import com.rieltor.domain.model.RepostDestination
import com.rieltor.domain.model.StoredThreadsTokens
import com.rieltor.domain.repository.ThreadsTokenRepository
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
        assertEquals("Опис квартири", createRequests.last()["text"])
        assertEquals("container-3", publishCreationId)
    }

    @Test
    fun `single photo uses image container and limits Threads text`() = runBlocking {
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

        ThreadsPhotoPublisher(client, auth, json, statusPollDelayMillis = 0)
            .publish(listOf("https://api.example/media/one.jpg"), "а".repeat(700))

        assertEquals("IMAGE", createdParameters["media_type"])
        assertEquals(500, createdParameters.getValue("text").length)
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
