package com.rieltor.tiktok

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Successful response from POST /v2/oauth/token/
 * Docs: https://developers.tiktok.com/doc/oauth-user-access-token-management
 */
@Serializable
data class TikTokTokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    @SerialName("open_id") val openId: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("refresh_expires_in") val refreshExpiresIn: Long? = null,
    @SerialName("scope") val scope: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
    // Present only on error
    @SerialName("error") val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)
