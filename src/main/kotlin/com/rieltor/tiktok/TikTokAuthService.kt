package com.rieltor.tiktok

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Instant

class TikTokAuthException(message: String) : Exception(message)

object TikTokAuthService {

    private val logger = LoggerFactory.getLogger(TikTokAuthService::class.java)

    private val jsonParser = Json { ignoreUnknownKeys = true }

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(jsonParser)
        }
    }

    /** Masks all but the first/last few characters of a secret value for safe logging. */
    private fun mask(value: String, keep: Int = 4): String =
        if (value.length <= keep * 2) "*".repeat(value.length)
        else "${value.take(keep)}...${value.takeLast(keep)} (len=${value.length})"

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
        logger.info(
            "Exchanging authorization code for tokens. code={}, redirect_uri={}, client_key={}",
            mask(code), TikTokConfig.redirectUri, mask(TikTokConfig.clientKey)
        )

        val httpResponse = postTokenRequest(
            Parameters.build {
                append("client_key", TikTokConfig.clientKey)
                append("client_secret", TikTokConfig.clientSecret)
                append("code", code)
                append("grant_type", "authorization_code")
                append("redirect_uri", TikTokConfig.redirectUri)
            }
        )

        val rawBody = httpResponse.bodyAsText()
        logger.info("TikTok token endpoint responded. status={}, body={}", httpResponse.status, rawBody)

        val response = jsonParser.decodeFromString<TikTokTokenResponse>(rawBody)
        return response.toStoredTokensOrThrow()
    }

    /** Refreshes an access token using a stored refresh token. */
    suspend fun refreshTokens(refreshToken: String): StoredTokens {
        logger.info("Refreshing access token. refresh_token={}", mask(refreshToken))

        val httpResponse = postTokenRequest(
            Parameters.build {
                append("client_key", TikTokConfig.clientKey)
                append("client_secret", TikTokConfig.clientSecret)
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
            }
        )

        val rawBody = httpResponse.bodyAsText()
        logger.info("TikTok token endpoint responded (refresh). status={}, body={}", httpResponse.status, rawBody)

        val response = jsonParser.decodeFromString<TikTokTokenResponse>(rawBody)
        return response.toStoredTokensOrThrow()
    }

    /**
     * TikTok requires a bare application/x-www-form-urlencoded Content-Type.
     * FormDataContent adds a UTF-8 charset parameter, which the token endpoint rejects.
     */
    private suspend fun postTokenRequest(parameters: Parameters): HttpResponse =
        httpClient.post(TikTokConfig.TOKEN_URL) {
            header(HttpHeaders.CacheControl, "no-cache")
            setBody(TextContent(parameters.formUrlEncode(), ContentType.Application.FormUrlEncoded))
        }

    /** Returns a valid access token for the given account, refreshing if needed. */
    suspend fun getValidAccessToken(openId: String): String {
        val tokens = TokenStore.get(openId)
            ?: throw TikTokAuthException("No tokens stored for openId=$openId. Re-authorize first.")

        if (!TokenStore.isAccessTokenExpired(tokens)) {
            logger.info("Using cached access token for openId={}", openId)
            return tokens.accessToken
        }

        logger.info("Cached access token expired for openId={}, refreshing", openId)
        val refreshed = refreshTokens(tokens.refreshToken)
        TokenStore.save(refreshed)
        return refreshed.accessToken
    }

    private fun TikTokTokenResponse.toStoredTokensOrThrow(): StoredTokens {
        if (error != null || accessToken == null || refreshToken == null || openId == null) {
            logger.warn(
                "TikTok token request failed. error={}, error_description={}, scope={}, token_type={}",
                error, errorDescription, scope, tokenType
            )
            throw TikTokAuthException("TikTok token request failed: $error - $errorDescription")
        }
        logger.info(
            "TikTok token request succeeded. openId={}, scope={}, expiresIn={}, refreshExpiresIn={}",
            openId, scope, expiresIn, refreshExpiresIn
        )
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
