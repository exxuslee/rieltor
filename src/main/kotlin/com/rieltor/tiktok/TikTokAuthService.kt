package com.rieltor.tiktok

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import java.time.Instant

class TikTokAuthException(message: String) : Exception(message)

object TikTokAuthService {

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    /** Builds the URL the user's browser should be redirected to. */
    fun buildAuthorizeUrl(state: String): String {
        val scopeParam = TikTokConfig.scopes.joinToString(",")
        return URLBuilder(TikTokConfig.AUTHORIZE_URL).apply {
            parameters.append("client_key", TikTokConfig.clientKey)
            parameters.append("scope", scopeParam)
            parameters.append("response_type", "code")
            parameters.append("redirect_uri", TikTokConfig.redirectUri)
            parameters.append("state", state)
        }.buildString()
    }

    /** Exchanges an authorization `code` (from the callback) for tokens. */
    suspend fun exchangeCodeForTokens(code: String): StoredTokens {
        val response: TikTokTokenResponse = httpClient.submitForm(
            url = TikTokConfig.TOKEN_URL,
            formParameters = Parameters.build {
                append("client_key", TikTokConfig.clientKey)
                append("client_secret", TikTokConfig.clientSecret)
                append("code", code)
                append("grant_type", "authorization_code")
                append("redirect_uri", TikTokConfig.redirectUri)
            }
        ).body()

        return response.toStoredTokensOrThrow()
    }

    /** Refreshes an access token using a stored refresh token. */
    suspend fun refreshTokens(refreshToken: String): StoredTokens {
        val response: TikTokTokenResponse = httpClient.submitForm(
            url = TikTokConfig.TOKEN_URL,
            formParameters = Parameters.build {
                append("client_key", TikTokConfig.clientKey)
                append("client_secret", TikTokConfig.clientSecret)
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
            }
        ).body()

        return response.toStoredTokensOrThrow()
    }

    /** Returns a valid access token for the given account, refreshing if needed. */
    suspend fun getValidAccessToken(openId: String): String {
        val tokens = TokenStore.get(openId)
            ?: throw TikTokAuthException("No tokens stored for openId=$openId. Re-authorize first.")

        if (!TokenStore.isAccessTokenExpired(tokens)) {
            return tokens.accessToken
        }

        val refreshed = refreshTokens(tokens.refreshToken)
        TokenStore.save(refreshed)
        return refreshed.accessToken
    }

    private fun TikTokTokenResponse.toStoredTokensOrThrow(): StoredTokens {
        if (error != null || accessToken == null || refreshToken == null || openId == null) {
            throw TikTokAuthException("TikTok token request failed: $error - $errorDescription")
        }
        val now = Instant.now().epochSecond
        return StoredTokens(
            openId = openId,
            accessToken = accessToken,
            refreshToken = refreshToken,
            accessTokenExpiresAt = now + (expiresIn ?: 0L),
            refreshTokenExpiresAt = now + (refreshExpiresIn ?: 0L),
        )
    }
}
