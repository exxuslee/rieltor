package com.rieltor.infrastructure.google

import com.rieltor.domain.model.StoredGoogleDriveTokens
import com.rieltor.domain.repository.GoogleDriveTokenRepository
import com.rieltor.infrastructure.config.ApplicationSettings
import io.ktor.client.*
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import java.time.Instant
import java.io.IOException

class GoogleDriveAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

class GoogleDriveAuthService(
    private val httpClient: HttpClient,
    private val settings: ApplicationSettings,
    private val tokens: GoogleDriveTokenRepository,
    private val json: Json,
    private val tokenRetryDelayMillis: Long = TOKEN_RETRY_DELAY_MILLIS,
) {
    fun buildAuthorizeUrl(state: String): String = URLBuilder(AUTHORIZE_URL).apply {
        parameters.append("client_id", settings.googleClientId)
        parameters.append("redirect_uri", settings.googleRedirectUri)
        parameters.append("response_type", "code")
        parameters.append("scope", DRIVE_READONLY_SCOPE)
        parameters.append("access_type", "offline")
        parameters.append("include_granted_scopes", "true")
        parameters.append("prompt", "consent")
        parameters.append("state", state)
        parameters.append("login_hint", GOOGLE_ACCOUNT_HINT)
    }.buildString()

    suspend fun exchangeCodeForTokens(code: String): StoredGoogleDriveTokens {
        val previousRefreshToken = tokens.load()?.refreshToken
        return requestTokens(
            Parameters.build {
                append("code", code)
                append("client_id", settings.googleClientId)
                append("client_secret", settings.googleClientSecret)
                append("redirect_uri", settings.googleRedirectUri)
                append("grant_type", "authorization_code")
            },
            fallbackRefreshToken = previousRefreshToken,
        ).also(tokens::save)
    }

    suspend fun validAccessToken(): String {
        val current = tokens.load()
            ?: throw GoogleDriveAuthException(
                "Google Drive account is not connected. Open /auth/google/login first."
            )
        if (Instant.now().epochSecond < current.accessTokenExpiresAt - EXPIRY_SAFETY_SECONDS) {
            return current.accessToken
        }
        return requestTokens(
            Parameters.build {
                append("client_id", settings.googleClientId)
                append("client_secret", settings.googleClientSecret)
                append("refresh_token", current.refreshToken)
                append("grant_type", "refresh_token")
            },
            fallbackRefreshToken = current.refreshToken,
        ).also(tokens::save).accessToken
    }

    private suspend fun requestTokens(
        parameters: Parameters,
        fallbackRefreshToken: String?,
    ): StoredGoogleDriveTokens {
        var lastTransientError: Throwable? = null
        repeat(TOKEN_REQUEST_MAX_ATTEMPTS) { attempt ->
            try {
                val response = httpClient.post(TOKEN_URL) {
                    header(HttpHeaders.CacheControl, "no-cache")
                    setBody(TextContent(parameters.formUrlEncode(), ContentType.Application.FormUrlEncoded))
                }
                val raw = response.bodyAsText()
                if (response.status.value == 429 || response.status.value >= 500) {
                    lastTransientError = GoogleDriveAuthException(
                        "Google token endpoint is temporarily unavailable (HTTP ${response.status.value})."
                    )
                    if (attempt + 1 < TOKEN_REQUEST_MAX_ATTEMPTS) {
                        delay(tokenRetryDelayMillis * (attempt + 1))
                        return@repeat
                    }
                    throw lastTransientError
                }
                return parseTokens(response, raw, fallbackRefreshToken)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!error.isTransientTokenFailure()) throw error
                lastTransientError = error
                if (attempt + 1 < TOKEN_REQUEST_MAX_ATTEMPTS) {
                    delay(tokenRetryDelayMillis * (attempt + 1))
                }
            }
        }
        throw GoogleDriveAuthException(
            "Google token request timed out after $TOKEN_REQUEST_MAX_ATTEMPTS attempts.",
            lastTransientError,
        )
    }

    private fun parseTokens(
        response: HttpResponse,
        raw: String,
        fallbackRefreshToken: String?,
    ): StoredGoogleDriveTokens {
        val payload = runCatching { json.decodeFromString<GoogleTokenResponse>(raw) }
            .getOrElse {
                throw GoogleDriveAuthException(
                    "Google token endpoint returned an unreadable response (HTTP ${response.status.value})."
                )
            }
        if (!response.status.isSuccess() || payload.error != null || payload.accessToken == null) {
            throw GoogleDriveAuthException(
                "Google token request failed: ${payload.error ?: response.status.value} - " +
                        (payload.errorDescription ?: "unknown error")
            )
        }
        val refreshToken = payload.refreshToken ?: fallbackRefreshToken
        ?: throw GoogleDriveAuthException(
            "Google did not return a refresh token. Reconnect the account and grant offline access."
        )
        return StoredGoogleDriveTokens(
            accessToken = payload.accessToken,
            refreshToken = refreshToken,
            accessTokenExpiresAt = Instant.now().epochSecond + (payload.expiresIn ?: 0L),
        )
    }

    private fun Throwable.isTransientTokenFailure(): Boolean =
        this is HttpRequestTimeoutException || this is IOException

    private companion object {
        const val AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth"
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val DRIVE_READONLY_SCOPE = "https://www.googleapis.com/auth/drive.readonly"
        const val GOOGLE_ACCOUNT_HINT = "irinalinnik.lee@gmail.com"
        const val EXPIRY_SAFETY_SECONDS = 60L
        const val TOKEN_REQUEST_MAX_ATTEMPTS = 3
        const val TOKEN_RETRY_DELAY_MILLIS = 1_000L
    }
}
