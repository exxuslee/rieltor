package com.rieltor.application.service

import com.rieltor.application.port.OAuthProvider
import com.rieltor.infrastructure.oauth.OAuthStateStore

/** Result of an OAuth callback, independent of the transport that reports it. */
sealed interface OAuthCallbackResult {
    data class Connected(val message: String) : OAuthCallbackResult
    data class Declined(val message: String) : OAuthCallbackResult
    data object InvalidState : OAuthCallbackResult
}

/** Login and callback flow shared by every OAuth provider. */
class OAuthLoginService(
    private val provider: OAuthProvider,
    private val states: OAuthStateStore,
) {
    val providerId: String get() = provider.id

    fun authorizeUrl(): String = provider.authorizeUrl(states.issue())

    suspend fun complete(code: String?, state: String?, error: String?, errorDescription: String?): OAuthCallbackResult {
        if (error != null) {
            return OAuthCallbackResult.Declined(
                "${provider.title} authorization was not completed: $error - $errorDescription"
            )
        }
        if (code == null || state == null || !states.consume(state)) return OAuthCallbackResult.InvalidState

        val details = provider.exchangeCode(code)
        return OAuthCallbackResult.Connected(
            buildString {
                append(provider.title).append(" account connected successfully.\n")
                if (!details.isNullOrBlank()) append(details).append("\n")
                append("You can close this window.")
            }
        )
    }
}

/** All OAuth flows exposed by the application, so routing does not have to know each provider. */
class OAuthRegistry(val services: List<OAuthLoginService>)
