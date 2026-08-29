package com.rieltor.infrastructure.tiktok

import com.rieltor.domain.model.PublishReceipt
import com.rieltor.domain.model.RepostDestination
import com.rieltor.domain.repository.PhotoPublisher
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.charset.StandardCharsets

class TikTokPhotoPublisher(
    private val httpClient: HttpClient,
    private val auth: TikTokAuthService,
    private val json: Json,
    private val retryDelayMillis: Long = 500,
    private val minPublishIntervalMillis: Long = DEFAULT_MIN_PUBLISH_INTERVAL_MILLIS,
    private val rateLimitRetryDelayMillis: Long = DEFAULT_RATE_LIMIT_RETRY_DELAY_MILLIS,
    private val rateLimitMaxAttempts: Int = DEFAULT_RATE_LIMIT_MAX_ATTEMPTS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
) : PhotoPublisher {
    override val destination = RepostDestination.TIKTOK
    override val maxPhotoCount = MAX_PHOTO_COUNT
    private val publishMutex = Mutex()
    private var lastPublishAttemptAtMillis: Long? = null

    override suspend fun publish(photoUrls: List<String>, caption: String?): PublishReceipt {
        require(photoUrls.isNotEmpty()) { "At least one photo URL is required." }
        require(photoUrls.size <= MAX_PHOTO_COUNT) { "TikTok accepts at most $MAX_PHOTO_COUNT photos per post." }
        require(minPublishIntervalMillis >= 0) { "Minimum publish interval must not be negative." }
        require(rateLimitRetryDelayMillis >= 0) { "Rate limit retry delay must not be negative." }
        require(rateLimitMaxAttempts > 0) { "Rate limit max attempts must be positive." }

        return publishMutex.withLock {
            var lastRateLimitError: TikTokRateLimitException? = null
            repeat(rateLimitMaxAttempts) { attempt ->
                waitForPublishSlot()
                try {
                    return@withLock publishOnce(photoUrls, caption)
                } catch (error: TikTokRateLimitException) {
                    lastRateLimitError = error
                    if (attempt + 1 < rateLimitMaxAttempts) {
                        delayMillis(rateLimitRetryDelayMillis * (attempt + 1))
                    }
                }
            }
            throw requireNotNull(lastRateLimitError)
        }
    }

    private suspend fun waitForPublishSlot() {
        val previousAttempt = lastPublishAttemptAtMillis
        if (previousAttempt != null) {
            val remainingDelay = minPublishIntervalMillis - (nowMillis() - previousAttempt)
            if (remainingDelay > 0) delayMillis(remainingDelay)
        }
        lastPublishAttemptAtMillis = nowMillis()
    }

    private suspend fun publishOnce(photoUrls: List<String>, caption: String?): PublishReceipt {
        val accessToken = auth.validAccessToken()
        val creator = queryCreator(accessToken)
        // TikTok blocks unaudited clients from publishing anything except a private post.
        // Never fall back to a more public option: it is rejected by the API and could expose a listing unexpectedly.
        val privacy = creator.privacyLevelOptions.firstOrNull { it == "SELF_ONLY" }
            ?: throw TikTokAuthException(
                "TikTok did not allow SELF_ONLY privacy for this account. " +
                    "Available options: ${creator.privacyLevelOptions.joinToString().ifBlank { "none" }}. " +
                    "For an unaudited app, enable private posting for the authorized TikTok account or complete TikTok audit."
            )

        val normalizedCaption = caption.orEmpty().trim()
        val title = normalizedCaption.lineSequence().firstOrNull().orEmpty().take(90)
        val body = buildJsonObject {
            put("media_type", "PHOTO")
            put("post_mode", "DIRECT_POST")
            put("post_info", buildJsonObject {
                if (title.isNotBlank()) put("title", title)
                if (normalizedCaption.isNotBlank()) put("description", normalizedCaption.take(4000))
                put("privacy_level", privacy)
                put("disable_comment", false)
                put("auto_add_music", true)
                put("brand_content_toggle", false)
                put("brand_organic_toggle", false)
            })
            put("source_info", buildJsonObject {
                put("source", "PULL_FROM_URL")
                put("photo_cover_index", 0)
                put("photo_images", buildJsonArray {
                    photoUrls.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                })
            })
        }
        val response = httpClient.post(PHOTO_POST_URL) {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            setBody(TextContent(json.encodeToString(body), ContentType.Application.Json.withCharset(StandardCharsets.UTF_8)))
        }
        val payload = json.decodeFromString<PublishResponse>(response.bodyAsText())
        payload.error.ensureOk("photo publish")
        val publishId = payload.data?.publishId
            ?: throw TikTokAuthException("TikTok photo publish response has no publish_id.")
        return PublishReceipt(publishId, creator.nickname, privacy)
    }

    private suspend fun queryCreator(accessToken: String): CreatorInfo {
        var lastError: IOException? = null
        repeat(CREATOR_INFO_MAX_ATTEMPTS) { attempt ->
            try {
                return queryCreatorOnce(accessToken)
            } catch (error: IOException) {
                lastError = error
                if (attempt + 1 < CREATOR_INFO_MAX_ATTEMPTS) {
                    delay(retryDelayMillis * (attempt + 1))
                }
            }
        }
        throw TikTokAuthException(
            "TikTok creator info request timed out after $CREATOR_INFO_MAX_ATTEMPTS attempts: " +
                (lastError?.message ?: "network error")
        )
    }

    private suspend fun queryCreatorOnce(accessToken: String): CreatorInfo {
        val response = httpClient.post(CREATOR_INFO_URL) {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            setBody(TextContent("{}", ContentType.Application.Json.withCharset(StandardCharsets.UTF_8)))
        }
        val payload = json.decodeFromString<CreatorInfoResponse>(response.bodyAsText())
        payload.error.ensureOk("creator info")
        return payload.data
            ?.takeIf { it.nickname.isNotBlank() }
            ?: throw TikTokAuthException("TikTok creator info response has no creator_nickname.")
    }

    private fun TikTokApiError?.ensureOk(operation: String) {
        if (this != null && code != "ok") {
            val errorMessage = "TikTok $operation failed: $code - $message (log_id=$logId)"
            if (code == "rate_limit_exceeded") throw TikTokRateLimitException(errorMessage)
            throw TikTokAuthException(errorMessage)
        }
    }

    companion object {
        private const val CREATOR_INFO_URL = "https://open.tiktokapis.com/v2/post/publish/creator_info/query/"
        private const val PHOTO_POST_URL = "https://open.tiktokapis.com/v2/post/publish/content/init/"
        private const val MAX_PHOTO_COUNT = 35
        private const val CREATOR_INFO_MAX_ATTEMPTS = 3
        private const val DEFAULT_MIN_PUBLISH_INTERVAL_MILLIS = 11_000L
        private const val DEFAULT_RATE_LIMIT_RETRY_DELAY_MILLIS = 60_000L
        private const val DEFAULT_RATE_LIMIT_MAX_ATTEMPTS = 3
    }
}

private class TikTokRateLimitException(message: String) : TikTokAuthException(message)
