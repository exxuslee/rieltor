package com.rieltor.infrastructure.tiktok

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TikTokTokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    @SerialName("open_id") val openId: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("refresh_expires_in") val refreshExpiresIn: Long? = null,
    val scope: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)

@Serializable
data class TikTokApiError(
    val code: String = "unknown",
    val message: String = "Unknown TikTok API error",
    @SerialName("log_id") val logId: String? = null,
)

@Serializable
data class CreatorInfoResponse(
    val data: CreatorInfo? = null,
    val error: TikTokApiError? = null,
)

@Serializable
data class CreatorInfo(
    @SerialName("creator_nickname") val nickname: String,
    @SerialName("privacy_level_options") val privacyLevelOptions: List<String> = emptyList(),
)

@Serializable
data class PublishResponse(
    val data: PublishData? = null,
    val error: TikTokApiError? = null,
)

@Serializable
data class PublishData(
    @SerialName("publish_id") val publishId: String? = null,
)
