package com.rieltor.tiktok

/**
 * App credentials, loaded from environment variables.
 *
 * Set these before running the server:
 *   TIKTOK_CLIENT_KEY      - "Client key" from TikTok Developer Portal
 *   TIKTOK_CLIENT_SECRET   - "Client secret" from TikTok Developer Portal
 *   TIKTOK_REDIRECT_URI    - must match EXACTLY what's registered in Login Kit,
 *                            e.g. https://api.rieltor.dpdns.org/auth/tiktok/callback
 *
 * Never hardcode the secret in source code or commit it to git.
 */
object TikTokConfig {
    val clientKey: String = getRequiredEnv("TIKTOK_CLIENT_KEY", dotenv)

    val clientSecret: String = getRequiredEnv("TIKTOK_CLIENT_SECRET", dotenv)

    val redirectUri: String = getRequiredEnv("TIKTOK_REDIRECT_URI", dotenv)

    // Scopes needed for this integration.
    val scopes = listOf("user.info.basic", "video.publish", "video.upload")

    const val AUTHORIZE_URL = "https://www.tiktok.com/v2/auth/authorize/"
    const val TOKEN_URL = "https://open.tiktokapis.com/v2/oauth/token/"
}
