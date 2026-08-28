package com.rieltor.infrastructure.tiktok

import com.rieltor.domain.model.StoredTokens
import com.rieltor.domain.port.TikTokTokenRepository
import com.rieltor.infrastructure.config.ApplicationSettings
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.content.TextContent
import io.ktor.http.formUrlEncode
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

open class TikTokAuthException(message: String) : Exception(message)

class TikTokAuthService(
    private val httpClient: HttpClient,
    private val settings: ApplicationSettings,
    private val tokens: TikTokTokenRepository,
    private val json: Json,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun buildAuthorizeUrl(state: String): String = URLBuilder(AUTHORIZE_URL).apply {
        parameters.append("client_key", settings.tikTokClientKey)
        parameters.append("scope", "user.info.basic,video.publish,video.upload")
        parameters.append("response_type", "code")
        parameters.append("redirect_uri", settings.tikTokRedirectUri)
        parameters.append("state", state)
    }.buildString()

    suspend fun exchangeCodeForTokens(code: String): StoredTokens {
        logger.info("Exchanging TikTok authorization code. code={}", mask(code))
        return requestTokens(
            Parameters.build {
                append("client_key", settings.tikTokClientKey)
                append("client_secret", settings.tikTokClientSecret)
                append("code", code)
                append("grant_type", "authorization_code")
                append("redirect_uri", settings.tikTokRedirectUri)
            }
        ).also(tokens::save)
    }

    suspend fun validAccessToken(): String {
        val current = tokens.latest()
            ?: throw TikTokAuthException("TikTok account is not connected. Open /auth/tiktok/login first.")
        if (Instant.now().epochSecond < current.accessTokenExpiresAt - 60) return current.accessToken
        if (Instant.now().epochSecond >= current.refreshTokenExpiresAt) {
            throw TikTokAuthException("TikTok refresh token expired. Reconnect the account.")
        }
        return requestTokens(
            Parameters.build {
                append("client_key", settings.tikTokClientKey)
                append("client_secret", settings.tikTokClientSecret)
                append("grant_type", "refresh_token")
                append("refresh_token", current.refreshToken)
            }
        ).also(tokens::save).accessToken
    }

    private suspend fun requestTokens(parameters: Parameters): StoredTokens {
        val response: HttpResponse = httpClient.post(TOKEN_URL) {
            header(HttpHeaders.CacheControl, "no-cache")
            setBody(TextContent(parameters.formUrlEncode(), ContentType.Application.FormUrlEncoded))
        }
        val raw = response.bodyAsText()
        logger.info("TikTok token endpoint responded. status={}", response.status)
        val payload = json.decodeFromString<TikTokTokenResponse>(raw)
        if (payload.error != null || payload.accessToken == null || payload.refreshToken == null || payload.openId == null) {
            throw TikTokAuthException("TikTok token request failed: ${payload.error} - ${payload.errorDescription}")
        }
        val now = Instant.now().epochSecond
        return StoredTokens(
            openId = payload.openId,
            accessToken = payload.accessToken,
            refreshToken = payload.refreshToken,
            accessTokenExpiresAt = now + (payload.expiresIn ?: 0),
            refreshTokenExpiresAt = now + (payload.refreshExpiresIn ?: 0),
        )
    }

    private fun mask(value: String): String =
        if (value.length < 9) "***" else "${value.take(4)}...${value.takeLast(4)}"

    companion object {
        private const val AUTHORIZE_URL = "https://www.tiktok.com/v2/auth/authorize/"
        private const val TOKEN_URL = "https://open.tiktokapis.com/v2/oauth/token/"
    }
}

class OAuthStateStore {
    private val states = ConcurrentHashMap<String, Long>()

    fun issue(): String = UUID.randomUUID().toString().also {
        states[it] = Instant.now().epochSecond
    }

    fun consume(state: String): Boolean {
        val issuedAt = states.remove(state) ?: return false
        return Instant.now().epochSecond - issuedAt <= 10 * 60
    }
}
