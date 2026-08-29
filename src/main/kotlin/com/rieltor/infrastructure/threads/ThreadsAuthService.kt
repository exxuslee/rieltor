package com.rieltor.infrastructure.threads

import com.rieltor.domain.model.StoredThreadsTokens
import com.rieltor.domain.repository.ThreadsTokenRepository
import com.rieltor.infrastructure.config.ApplicationSettings
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.content.TextContent
import io.ktor.http.formUrlEncode
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import java.time.Instant

open class ThreadsAuthException(message: String) : Exception(message)

class ThreadsAuthService(
    private val httpClient: HttpClient,
    private val settings: ApplicationSettings,
    private val tokens: ThreadsTokenRepository,
    private val json: Json,
) {
    fun buildAuthorizeUrl(state: String): String {
        requireConfigured()
        return URLBuilder(AUTHORIZE_URL).apply {
            parameters.append("client_id", settings.threadsAppId)
            parameters.append("scope", "threads_basic,threads_content_publish")
            parameters.append("response_type", "code")
            parameters.append("redirect_uri", settings.threadsRedirectUri)
            parameters.append("state", state)
        }.buildString()
    }

    suspend fun exchangeCodeForTokens(code: String): StoredThreadsTokens {
        requireConfigured()
        val short = requestToken(
            TOKEN_URL,
            Parameters.build {
                append("client_id", settings.threadsAppId)
                append("client_secret", settings.threadsAppSecret)
                append("grant_type", "authorization_code")
                append("redirect_uri", settings.threadsRedirectUri)
                append("code", code)
            },
            post = true,
        )
        val userId = short.userId ?: throw ThreadsAuthException("Threads token response has no user_id.")
        val long = requestToken(
            LONG_LIVED_TOKEN_URL,
            Parameters.build {
                append("grant_type", "th_exchange_token")
                append("client_secret", settings.threadsAppSecret)
                append("access_token", requireNotNull(short.accessToken))
            },
        )
        return long.toStored(userId).also(tokens::save)
    }

    suspend fun validAccessToken(): String {
        val current = tokens.load()
            ?: throw ThreadsAuthException("Threads account is not connected. Open /auth/threads/login first.")
        val now = Instant.now().epochSecond
        if (now >= current.accessTokenExpiresAt) {
            throw ThreadsAuthException("Threads access token expired. Reconnect the account.")
        }
        if (now < current.accessTokenExpiresAt - REFRESH_WINDOW_SECONDS) return current.accessToken
        return requestToken(
            REFRESH_TOKEN_URL,
            Parameters.build {
                append("grant_type", "th_refresh_token")
                append("access_token", current.accessToken)
            },
        ).toStored(current.userId).also(tokens::save).accessToken
    }

    fun connectedUserId(): String? = tokens.load()?.userId

    private suspend fun requestToken(url: String, requestParameters: Parameters, post: Boolean = false): ThreadsTokenResponse {
        val response = if (post) {
            httpClient.post(url) {
                setBody(TextContent(requestParameters.formUrlEncode(), ContentType.Application.FormUrlEncoded))
            }
        } else {
            httpClient.get(URLBuilder(url).apply { requestParameters.entries().forEach { (name, values) ->
                values.forEach { value -> parameters.append(name, value) }
            } }.build())
        }
        val payload = runCatching { json.decodeFromString<ThreadsTokenResponse>(response.bodyAsText()) }
            .getOrElse { throw ThreadsAuthException("Threads token endpoint returned an unreadable response (HTTP ${response.status.value}).") }
        if (!response.status.isSuccess() || payload.error != null || payload.accessToken.isNullOrBlank()) {
            throw ThreadsAuthException("Threads token request failed: ${payload.error?.message ?: response.status.value}")
        }
        return payload
    }

    private fun ThreadsTokenResponse.toStored(userId: String): StoredThreadsTokens = StoredThreadsTokens(
        userId = userId,
        accessToken = requireNotNull(accessToken),
        accessTokenExpiresAt = Instant.now().epochSecond + (expiresIn ?: DEFAULT_TOKEN_LIFETIME_SECONDS),
    )

    private fun requireConfigured() {
        if (!settings.threadsConfigured) throw ThreadsAuthException(
            "Threads is not configured. Set THREADS_APP_ID, THREADS_APP_SECRET and THREADS_REDIRECT_URI."
        )
    }

    private companion object {
        const val AUTHORIZE_URL = "https://threads.net/oauth/authorize"
        const val TOKEN_URL = "https://graph.threads.net/oauth/access_token"
        const val LONG_LIVED_TOKEN_URL = "https://graph.threads.net/access_token"
        const val REFRESH_TOKEN_URL = "https://graph.threads.net/refresh_access_token"
        const val DEFAULT_TOKEN_LIFETIME_SECONDS = 5_184_000L
        const val REFRESH_WINDOW_SECONDS = 604_800L
    }
}
