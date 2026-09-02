package com.rieltor.infrastructure.tiktok

import com.rieltor.domain.model.PublishReceipt
import com.rieltor.domain.model.RepostDestination
import com.rieltor.domain.repository.*
import com.rieltor.infrastructure.config.DEFAULT_REPOST_MAX_PHOTO_COUNT
import com.rieltor.infrastructure.config.TIKTOK_API_MAX_PHOTO_COUNT
import com.rieltor.infrastructure.config.TikTokMode
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
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets

class TikTokPhotoPublisher(
    private val httpClient: HttpClient,
    private val auth: TikTokAuthService,
    private val json: Json,
    private val retryDelayMillis: Long = 500,
    private val minPublishIntervalMillis: Long = DEFAULT_MIN_PUBLISH_INTERVAL_MILLIS,
    private val rateLimitRetryDelayMillis: Long = DEFAULT_RATE_LIMIT_RETRY_DELAY_MILLIS,
    private val rateLimitMaxAttempts: Int = DEFAULT_RATE_LIMIT_MAX_ATTEMPTS,
    private val statusPollIntervalMillis: Long = DEFAULT_STATUS_POLL_INTERVAL_MILLIS,
    private val statusPollMaxAttempts: Int = DEFAULT_STATUS_POLL_MAX_ATTEMPTS,
    private val tikTokMode: TikTokMode = TikTokMode.POST,
    override val maxPhotoCount: Int = DEFAULT_REPOST_MAX_PHOTO_COUNT,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
    private val publishRepository: TikTokPublishThrottleRepository? = null,
    private val globalCooldownMillis: Long = DEFAULT_GLOBAL_COOLDOWN_MILLIS,
) : PhotoPublisher {
    private val logger = LoggerFactory.getLogger(javaClass)
    override val destination = RepostDestination.TIKTOK
    private val publishMutex = Mutex()
    private var lastPublishAttemptAtMillis: Long? = null

    override suspend fun awaitPublishSlot() {
        publishMutex.withLock {
            reconcileTrackedPublishes(auth.validAccessToken())
        }
    }

    override suspend fun pendingDiagnostics(): PublisherPendingDiagnostics? = publishMutex.withLock {
        val repository = publishRepository ?: return@withLock null
        val snapshot = refreshTrackedPublishes(auth.validAccessToken(), repository)
        PublisherPendingDiagnostics(
            destination = destination,
            trackedCount = snapshot.trackedCount,
            pendingCount = snapshot.active.size,
            statuses = snapshot.active.map { (publish, status) -> "${publish.publishId}:$status" },
        )
    }

    override suspend fun publish(photoUrls: List<String>, caption: String?): PublishReceipt {
        require(photoUrls.isNotEmpty()) { "At least one photo URL is required." }
        require(maxPhotoCount in 1..TIKTOK_API_MAX_PHOTO_COUNT) {
            "TikTok photo limit must be between 1 and $TIKTOK_API_MAX_PHOTO_COUNT."
        }
        require(photoUrls.size <= maxPhotoCount) {
            "This TikTok publisher accepts at most $maxPhotoCount photos per post."
        }
        require(minPublishIntervalMillis >= 0) { "Minimum publish interval must not be negative." }
        require(rateLimitRetryDelayMillis >= 0) { "Rate limit retry delay must not be negative." }
        require(rateLimitMaxAttempts > 0) { "Rate limit max attempts must be positive." }
        require(statusPollIntervalMillis >= 0) { "Status poll interval must not be negative." }
        require(statusPollMaxAttempts > 0) { "Status poll max attempts must be positive." }

        validatePublicPhotoUrls(photoUrls)
        val pendingPublish = publishMutex.withLock {
            var lastRateLimitError: TikTokRateLimitException? = null
            repeat(rateLimitMaxAttempts) { attempt ->
                val accessToken = auth.validAccessToken()
                reconcileTrackedPublishes(accessToken)
                waitForPublishSlot()
                try {
                    return@withLock initializePublish(accessToken, photoUrls, caption)
                } catch (error: TikTokRateLimitException) {
                    lastRateLimitError = error
                    if (attempt + 1 < rateLimitMaxAttempts) {
                        delayMillis(rateLimitRetryDelayMillis * (attempt + 1))
                    }
                }
            }
            throw requireNotNull(lastRateLimitError)
        }
        awaitPublishCompletion(pendingPublish.accessToken, pendingPublish.publishId)
        return PublishReceipt(
            pendingPublish.publishId,
            pendingPublish.creatorName,
            pendingPublish.privacyLevel,
        )
    }

    private suspend fun waitForPublishSlot() {
        val previousAttempt = lastPublishAttemptAtMillis
        if (previousAttempt != null) {
            val remainingDelay = minPublishIntervalMillis - (nowMillis() - previousAttempt)
            if (remainingDelay > 0) delayMillis(remainingDelay)
        }
        lastPublishAttemptAtMillis = nowMillis()
    }

    private suspend fun initializePublish(
        accessToken: String,
        photoUrls: List<String>,
        caption: String?,
    ): PendingTikTokPublish {
        val creator = queryCreator(accessToken)
        // TikTok blocks unaudited clients from publishing anything except a private post.
        // Never fall back to a more public option: it is rejected by the API and could expose a listing unexpectedly.
        val privacy = if (tikTokMode == TikTokMode.POST) {
            creator.privacyLevelOptions.firstOrNull { it == "SELF_ONLY" }
                ?: throw TikTokAuthException(
                    "TikTok did not allow SELF_ONLY privacy for this account. " +
                        "Available options: ${creator.privacyLevelOptions.joinToString().ifBlank { "none" }}. " +
                        "For an unaudited app, enable private posting for the authorized TikTok account or complete TikTok audit."
                )
        } else {
            "DRAFT"
        }

        val normalizedCaption = caption.orEmpty().trim()
        val title = normalizedCaption.lineSequence().firstOrNull().orEmpty().take(90)
        val body = buildJsonObject {
            put("media_type", "PHOTO")
            put("post_mode", if (tikTokMode == TikTokMode.POST) "DIRECT_POST" else "MEDIA_UPLOAD")
            put("post_info", buildJsonObject {
                if (title.isNotBlank()) put("title", title)
                if (normalizedCaption.isNotBlank()) put("description", normalizedCaption.take(4000))
                if (tikTokMode == TikTokMode.POST) {
                    put("privacy_level", privacy)
                    put("disable_comment", false)
                    put("auto_add_music", true)
                    put("brand_content_toggle", false)
                    put("brand_organic_toggle", false)
                }
            })
            put("source_info", buildJsonObject {
                put("source", "PULL_FROM_URL")
                put("photo_cover_index", 0)
                put("photo_images", buildJsonArray {
                    photoUrls.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                })
            })
        }
        logger.info(
            "TikTok photo repost initialization started. creator={}, mode={}, privacy={}, photoCount={}, photoHosts={}, captionChars={}",
            creator.nickname,
            tikTokMode,
            privacy,
            photoUrls.size,
            photoUrls.mapNotNull(::hostOf).distinct(),
            normalizedCaption.length,
        )
        val response = httpClient.post(PHOTO_POST_URL) {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            setBody(TextContent(json.encodeToString(body), ContentType.Application.Json.withCharset(StandardCharsets.UTF_8)))
        }
        val payload = json.decodeFromString<PublishResponse>(response.bodyAsText())
        payload.error.ensureOk("photo publish")
        val publishId = payload.data?.publishId
            ?: throw TikTokAuthException("TikTok photo publish response has no publish_id.")
        publishRepository?.trackPublish(publishId, tikTokMode.name, nowMillis())
        logger.info(
            "TikTok photo repost accepted for processing. publishId={}, mode={}, httpStatus={}, logId={}",
            publishId,
            tikTokMode,
            response.status.value,
            payload.error?.logId,
        )
        return PendingTikTokPublish(accessToken, publishId, creator.nickname, privacy)
    }

    private suspend fun validatePublicPhotoUrls(photoUrls: List<String>) {
        photoUrls.forEachIndexed { index, url ->
            val response = runCatching { httpClient.head(url) }
                .getOrElse { error ->
                    throw TikTokAuthException(
                        "Public photo preflight failed for photo ${index + 1}/${photoUrls.size}: " +
                            (error.message ?: error.javaClass.simpleName)
                    )
                }
            logger.info(
                "TikTok public photo preflight. photo={}/{}, host={}, httpStatus={}, contentType={}, contentLength={}",
                index + 1,
                photoUrls.size,
                hostOf(url),
                response.status.value,
                response.headers[HttpHeaders.ContentType],
                response.headers[HttpHeaders.ContentLength],
            )
            if (!response.status.isSuccess()) {
                throw TikTokAuthException(
                    "Public photo is not readable before TikTok initialization. " +
                        "photo=${index + 1}/${photoUrls.size}, host=${hostOf(url)}, HTTP ${response.status.value}"
                )
            }
        }
    }

    private suspend fun awaitPublishCompletion(accessToken: String, publishId: String) {
        var lastStatus: String? = null
        repeat(statusPollMaxAttempts) { attempt ->
            if (attempt > 0) delayMillis(statusPollIntervalMillis)
            val fetched = fetchPublishStatus(accessToken, publishId)
            val statusData = fetched.data
            val statusChanged = statusData.status != lastStatus
            if (statusChanged || attempt + 1 == statusPollMaxAttempts) {
                logPublishStatus(publishId, statusData, attempt + 1, statusPollMaxAttempts, fetched)
            }
            lastStatus = statusData.status
            when {
                tikTokMode == TikTokMode.POST && statusData.status == "PUBLISH_COMPLETE" -> {
                    publishRepository?.removeTrackedPublish(publishId)
                    return
                }
                tikTokMode == TikTokMode.DRAFT &&
                    statusData.status in setOf("SEND_TO_USER_INBOX", "PUBLISH_COMPLETE") -> {
                    // SEND_TO_USER_INBOX is intentionally retained: it still consumes a pending-share slot
                    // until the user publishes it in TikTok or the 24-hour window expires.
                    if (statusData.status == "PUBLISH_COMPLETE") {
                        publishRepository?.removeTrackedPublish(publishId)
                    }
                    return
                }
                statusData.status == "FAILED" -> {
                    publishRepository?.removeTrackedPublish(publishId)
                    val message = "TikTok photo post failed after initialization. publishId=$publishId, " +
                        "reason=${statusData.failReason ?: "unknown"}"
                    if (statusData.failReason == DAILY_POST_LIMIT_CODE) {
                        pauseGlobalPublishing("daily creator limit reported by status/fetch")
                        throw TikTokDailyPostLimitException(message)
                    }
                    throw TikTokAuthException(message)
                }
                statusData.status in setOf("PROCESSING_UPLOAD", "PROCESSING_DOWNLOAD") -> Unit
                else -> throw TikTokAuthException(
                    "TikTok returned an unexpected publish status for $tikTokMode mode. " +
                        "publishId=$publishId, status=${statusData.status}"
                )
            }
        }
        throw TikTokAuthException(
            "TikTok photo post did not reach a final status after $statusPollMaxAttempts checks. " +
                "publishId=$publishId, lastStatus=${lastStatus ?: "unknown"}"
        )
    }

    private suspend fun reconcileTrackedPublishes(accessToken: String) {
        val repository = publishRepository ?: return
        val snapshot = refreshTrackedPublishes(accessToken, repository)
        if (snapshot.active.size >= MAX_PENDING_SHARES) {
            pauseGlobalPublishing("locally tracked pending-share limit")
            throw TikTokPendingShareLimitException(
                "TikTok has ${snapshot.active.size} locally tracked pending shares; waiting for status/fetch to report " +
                    "PUBLISH_COMPLETE/FAILED or for the 24-hour pending window to expire."
            )
        }
    }

    private suspend fun refreshTrackedPublishes(
        accessToken: String,
        repository: TikTokPublishThrottleRepository,
    ): TrackedPublishSnapshot {
        val tracked = repository.trackedPublishes(nowMillis(), PENDING_SHARE_WINDOW_MILLIS)
        if (tracked.isEmpty()) return TrackedPublishSnapshot(0, emptyList())

        val active = mutableListOf<Pair<TrackedTikTokPublish, String>>()
        tracked.forEach { publish ->
            val fetched = fetchPublishStatus(accessToken, publish.publishId)
            val status = fetched.data.status
            logPublishStatus(publish.publishId, fetched.data, 1, 1, fetched)
            if (status == "FAILED" || status == "PUBLISH_COMPLETE") {
                repository.removeTrackedPublish(publish.publishId)
            } else {
                active += publish to status
            }
        }
        logger.info(
            "TikTok pending-share reconciliation completed. tracked={}, pending={}, limit={}, activePublishes={}",
            tracked.size,
            active.size,
            MAX_PENDING_SHARES,
            active.joinToString(prefix = "[", postfix = "]") { (publish, status) ->
                "${publish.publishId}:$status"
            },
        )
        return TrackedPublishSnapshot(tracked.size, active)
    }

    private fun pauseGlobalPublishing(reason: String) {
        val blockedUntil = nowMillis() + globalCooldownMillis
        publishRepository?.blockUntil(blockedUntil)
        logger.warn(
            "Global repost orchestrator paused after TikTok error. reason={}, cooldownHours={}, blockedUntilMillis={}",
            reason,
            globalCooldownMillis / 3_600_000L,
            blockedUntil,
        )
    }

    private suspend fun fetchPublishStatus(accessToken: String, publishId: String): FetchedPublishStatus {
        val response = httpClient.post(PUBLISH_STATUS_URL) {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            setBody(TextContent(
                json.encodeToString(buildJsonObject { put("publish_id", publishId) }),
                ContentType.Application.Json.withCharset(StandardCharsets.UTF_8),
            ))
        }
        val payload = json.decodeFromString<PublishStatusResponse>(response.bodyAsText())
        payload.error.ensureOk("publish status")
        val data = payload.data
            ?: throw TikTokAuthException("TikTok publish status response has no data. publishId=$publishId")
        publishRepository?.updateTrackedStatus(publishId, data.status, nowMillis())
        return FetchedPublishStatus(data, response.status.value, payload.error?.logId)
    }

    private fun logPublishStatus(
        publishId: String,
        statusData: PublishStatusData,
        attempt: Int,
        maxAttempts: Int,
        fetched: FetchedPublishStatus,
    ) {
        logger.info(
            "TikTok photo post status. publishId={}, status={}, attempt={}/{}, failReason={}, uploadedBytes={}, downloadedBytes={}, postIds={}, httpStatus={}, logId={}",
            publishId,
            statusData.status,
            attempt,
            maxAttempts,
            statusData.failReason,
            statusData.uploadedBytes,
            statusData.downloadedBytes,
            statusData.publiclyAvailablePostIds,
            fetched.httpStatus,
            fetched.logId,
        )
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
        val creator = payload.data
            ?.takeIf { it.nickname.isNotBlank() }
            ?: throw TikTokAuthException("TikTok creator info response has no creator_nickname.")
        logger.info(
            "TikTok creator info received. username={}, nickname={}, privacyOptions={}, httpStatus={}, logId={}",
            creator.username.ifBlank { "unavailable" },
            creator.nickname,
            creator.privacyLevelOptions,
            response.status.value,
            payload.error?.logId,
        )
        return creator
    }

    private fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()

    private fun TikTokApiError?.ensureOk(operation: String) {
        if (this != null && code != "ok") {
            val errorMessage = "TikTok $operation failed: $code - $message (log_id=$logId)"
            if (code == DAILY_POST_LIMIT_CODE) {
                pauseGlobalPublishing("$operation: daily creator limit")
                throw TikTokDailyPostLimitException(errorMessage)
            }
            if (code == PENDING_SHARE_LIMIT_CODE) {
                pauseGlobalPublishing("$operation: pending-share limit")
                throw TikTokPendingShareLimitException(errorMessage)
            }
            if (code == "rate_limit_exceeded") throw TikTokRateLimitException(errorMessage)
            throw TikTokAuthException(errorMessage)
        }
    }

    private data class PendingTikTokPublish(
        val accessToken: String,
        val publishId: String,
        val creatorName: String,
        val privacyLevel: String,
    )

    private data class FetchedPublishStatus(
        val data: PublishStatusData,
        val httpStatus: Int,
        val logId: String?,
    )

    private data class TrackedPublishSnapshot(
        val trackedCount: Int,
        val active: List<Pair<TrackedTikTokPublish, String>>,
    )

    companion object {
        private const val CREATOR_INFO_URL = "https://open.tiktokapis.com/v2/post/publish/creator_info/query/"
        private const val PHOTO_POST_URL = "https://open.tiktokapis.com/v2/post/publish/content/init/"
        private const val PUBLISH_STATUS_URL = "https://open.tiktokapis.com/v2/post/publish/status/fetch/"
        private const val CREATOR_INFO_MAX_ATTEMPTS = 3
        private const val DEFAULT_MIN_PUBLISH_INTERVAL_MILLIS = 11_000L
        private const val DEFAULT_RATE_LIMIT_RETRY_DELAY_MILLIS = 60_000L
        private const val DEFAULT_RATE_LIMIT_MAX_ATTEMPTS = 3
        private const val DEFAULT_STATUS_POLL_INTERVAL_MILLIS = 5_000L
        private const val DEFAULT_STATUS_POLL_MAX_ATTEMPTS = 60
        private const val DAILY_POST_LIMIT_CODE = "spam_risk_too_many_posts"
        private const val PENDING_SHARE_LIMIT_CODE = "spam_risk_too_many_pending_share"
        private const val MAX_PENDING_SHARES = 5
        private const val PENDING_SHARE_WINDOW_MILLIS = 24 * 60 * 60 * 1_000L
        private const val DEFAULT_GLOBAL_COOLDOWN_MILLIS = 8 * 60 * 60 * 1_000L
    }
}

private class TikTokRateLimitException(message: String) : TikTokAuthException(message)
private class TikTokDailyPostLimitException(message: String) : TikTokAuthException(message)
private class TikTokPendingShareLimitException(message: String) : PublisherBackpressureException(message)
