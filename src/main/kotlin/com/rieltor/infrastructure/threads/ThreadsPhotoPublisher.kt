package com.rieltor.infrastructure.threads

import com.rieltor.domain.model.PublishReceipt
import com.rieltor.domain.model.RepostDestination
import com.rieltor.domain.repository.PhotoPublisher
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class ThreadsPhotoPublisher(
    private val httpClient: HttpClient,
    private val auth: ThreadsAuthService,
    private val json: Json,
    private val statusPollDelayMillis: Long = 500,
    private val maxStatusAttempts: Int = 20,
) : PhotoPublisher {
    private val logger = LoggerFactory.getLogger(javaClass)
    override val destination = RepostDestination.THREADS
    override val maxPhotoCount = MAX_PHOTO_COUNT

    override suspend fun publish(photoUrls: List<String>, caption: String?): PublishReceipt {
        require(photoUrls.isNotEmpty()) { "At least one photo URL is required." }
        require(photoUrls.size <= maxPhotoCount) { "Threads accepts at most $maxPhotoCount photos per carousel." }
        val token = auth.validAccessToken()
        logger.info("Threads publishing started. photoCount={}", photoUrls.size)
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
        val published = decodeId(response.status.value, response.bodyAsText(), response.status.isSuccess(), "publish", token)
        logger.info("Threads publishing completed. publishId={}", published)
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
        return decodeId(response.status.value, response.bodyAsText(), response.status.isSuccess(), "container creation mediaType=$mediaType", token)
    }

    private suspend fun waitUntilReady(containerId: String, token: String) {
        repeat(maxStatusAttempts) { attempt ->
            val response = httpClient.get("$GRAPH_URL/$containerId") {
                parameter("fields", "status,error_message")
                parameter("access_token", token)
            }
            val payload = runCatching { json.decodeFromString<ThreadsContainerStatus>(response.bodyAsText()) }
                .getOrElse { throw ThreadsAuthException("Threads container status returned an unreadable response (HTTP ${response.status.value}).") }
            if (!response.status.isSuccess() || payload.error != null) {
                failResponse(response.status.value, "container status", payload.error, token)
            }
            logger.info("Threads container status. containerId={}, attempt={}, status={}", containerId, attempt + 1, payload.status)
            when (payload.status) {
                "FINISHED" -> return
                "ERROR", "EXPIRED" -> throw ThreadsAuthException(
                    "Threads media container ${payload.status}: ${safe(payload.errorMessage ?: "unknown error", token)}"
                )
            }
            if (attempt + 1 < maxStatusAttempts) delay(statusPollDelayMillis)
        }
        throw ThreadsAuthException("Threads media container did not become ready in time.")
    }

    private fun decodeId(status: Int, raw: String, success: Boolean, operation: String, token: String): String {
        val payload = runCatching { json.decodeFromString<ThreadsIdResponse>(raw) }
            .getOrElse { throw ThreadsAuthException("Threads $operation returned an unreadable response (HTTP $status).") }
        if (!success || payload.error != null || payload.id.isNullOrBlank()) {
            failResponse(status, operation, payload.error, token)
        }
        return payload.id
    }

    private fun failResponse(status: Int, operation: String, error: ThreadsApiError?, token: String): Nothing {
        val details = "operation=$operation, HTTP=$status, type=${safe(error?.type.orEmpty(), token)}, " +
            "code=${error?.code}, subcode=${error?.errorSubcode}, transient=${error?.isTransient}, " +
            "traceId=${safe(error?.traceId.orEmpty(), token)}, message=${safe(error?.message ?: "Missing response id or API error", token)}"
        logger.warn("Threads API request failed. {}", details)
        throw ThreadsAuthException("Threads request failed: $details")
    }

    private fun safe(value: String, token: String): String = value
        .replace(token, "[REDACTED]")
        .replace(Regex("https?://[^\\s]+"), "[URL]")
        .replace('\n', ' ').replace('\r', ' ').take(1000)

    private companion object {
        const val GRAPH_URL = "https://graph.threads.net/v1.0"
        const val MAX_PHOTO_COUNT = 20
        const val MAX_TEXT_LENGTH = 500
    }
}
