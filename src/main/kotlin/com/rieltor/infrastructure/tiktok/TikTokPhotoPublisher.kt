package com.rieltor.infrastructure.tiktok

import com.rieltor.domain.model.PublishReceipt
import com.rieltor.domain.port.PhotoPublisher
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.withCharset
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets

class TikTokPhotoPublisher(
    private val httpClient: HttpClient,
    private val auth: TikTokAuthService,
    private val json: Json,
) : PhotoPublisher {
    override suspend fun publish(photoUrl: String, caption: String?): PublishReceipt {
        val accessToken = auth.validAccessToken()
        val creator = queryCreator(accessToken)
        val privacy = creator.privacyLevelOptions.firstOrNull { it == "SELF_ONLY" }
            ?: creator.privacyLevelOptions.firstOrNull()
            ?: throw TikTokAuthException("TikTok returned no allowed privacy levels for this creator.")

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
                put("photo_images", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(photoUrl)) })
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
        val response = httpClient.post(CREATOR_INFO_URL) {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            setBody(TextContent("{}", ContentType.Application.Json.withCharset(StandardCharsets.UTF_8)))
        }
        val payload = json.decodeFromString<CreatorInfoResponse>(response.bodyAsText())
        payload.error.ensureOk("creator info")
        return payload.data ?: throw TikTokAuthException("TikTok creator info response has no data.")
    }

    private fun TikTokApiError?.ensureOk(operation: String) {
        if (this != null && code != "ok") {
            throw TikTokAuthException("TikTok $operation failed: $code - $message (log_id=$logId)")
        }
    }

    companion object {
        private const val CREATOR_INFO_URL =
            "https://open.tiktokapis.com/v2/post/publish/creator_info/query/"
        private const val PHOTO_POST_URL =
            "https://open.tiktokapis.com/v2/post/publish/content/init/"
    }
}
