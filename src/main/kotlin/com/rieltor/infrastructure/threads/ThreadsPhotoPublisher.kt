package com.rieltor.infrastructure.threads

import com.rieltor.domain.model.PublishReceipt
import com.rieltor.domain.model.RepostDestination
import com.rieltor.domain.repository.PhotoPublisher
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

class ThreadsPhotoPublisher(
    private val httpClient: HttpClient,
    private val auth: ThreadsAuthService,
    private val json: Json,
    private val statusPollDelayMillis: Long = 500,
    private val maxStatusAttempts: Int = 20,
) : PhotoPublisher {
    override val destination = RepostDestination.THREADS
    override val maxPhotoCount = MAX_PHOTO_COUNT

    override suspend fun publish(photoUrls: List<String>, caption: String?): PublishReceipt {
        require(photoUrls.isNotEmpty()) { "At least one photo URL is required." }
        require(photoUrls.size <= maxPhotoCount) { "Threads accepts at most $maxPhotoCount photos per carousel." }
        val token = auth.validAccessToken()
        val text = caption.orEmpty().trim().take(MAX_TEXT_LENGTH)
        val containerId = if (photoUrls.size == 1) {
            createContainer(token, "IMAGE", mapOf("image_url" to photoUrls.single(), "text" to text))
        } else {
            val children = photoUrls.map { imageUrl ->
                createContainer(
                    token,
                    "IMAGE",
                    mapOf("image_url" to imageUrl, "is_carousel_item" to "true"),
                ).also { waitUntilReady(it, token) }
            }
            createContainer(
                token,
                "CAROUSEL",
                mapOf("children" to children.joinToString(","), "text" to text),
            )
        }
        waitUntilReady(containerId, token)
        val response = httpClient.post("$GRAPH_URL/me/threads_publish") {
            parameter("creation_id", containerId)
            parameter("access_token", token)
        }
        val published = decodeId(response.status.value, response.bodyAsText(), response.status.isSuccess(), "publish")
        return PublishReceipt(
            publishId = published,
            creatorName = auth.connectedUserId().orEmpty(),
            privacyLevel = "PROFILE_DEFAULT",
            destination = destination,
        )
    }

    private suspend fun createContainer(token: String, mediaType: String, values: Map<String, String>): String {
        val response = httpClient.post("$GRAPH_URL/me/threads") {
            parameter("media_type", mediaType)
            values.filterValues(String::isNotBlank).forEach { (name, value) -> parameter(name, value) }
            parameter("access_token", token)
        }
        return decodeId(response.status.value, response.bodyAsText(), response.status.isSuccess(), "container creation")
    }

    private suspend fun waitUntilReady(containerId: String, token: String) {
        repeat(maxStatusAttempts) { attempt ->
            val response = httpClient.get("$GRAPH_URL/$containerId") {
                parameter("fields", "status,error_message")
                parameter("access_token", token)
            }
            val payload = runCatching { json.decodeFromString<ThreadsContainerStatus>(response.bodyAsText()) }
                .getOrElse { throw ThreadsAuthException("Threads returned an unreadable container status.") }
            payload.error?.let { throw ThreadsAuthException("Threads status failed: ${it.message}") }
            when (payload.status) {
                "FINISHED" -> return
                "ERROR", "EXPIRED" -> throw ThreadsAuthException(
                    "Threads media container ${payload.status}: ${payload.errorMessage ?: "unknown error"}"
                )
            }
            if (attempt + 1 < maxStatusAttempts) delay(statusPollDelayMillis)
        }
        throw ThreadsAuthException("Threads media container did not become ready in time.")
    }

    private fun decodeId(status: Int, raw: String, success: Boolean, operation: String): String {
        val payload = runCatching { json.decodeFromString<ThreadsIdResponse>(raw) }
            .getOrElse { throw ThreadsAuthException("Threads $operation returned an unreadable response (HTTP $status).") }
        if (!success || payload.error != null || payload.id.isNullOrBlank()) {
            throw ThreadsAuthException("Threads $operation failed: ${payload.error?.message ?: "HTTP $status"}")
        }
        return payload.id
    }

    private companion object {
        const val GRAPH_URL = "https://graph.threads.net/v1.0"
        const val MAX_PHOTO_COUNT = 20
        const val MAX_TEXT_LENGTH = 500
    }
}
