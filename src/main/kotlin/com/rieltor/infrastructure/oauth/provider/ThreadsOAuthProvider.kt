package com.rieltor.infrastructure.oauth.provider

import com.rieltor.application.port.OAuthProvider
import com.rieltor.infrastructure.threads.ThreadsAuthService

class ThreadsOAuthProvider(private val auth: ThreadsAuthService) : OAuthProvider {
    override val id: String = "threads"
    override val title: String = "Threads"
    override fun authorizeUrl(state: String): String = auth.buildAuthorizeUrl(state)
    override suspend fun exchangeCode(code: String): String = "userId: ${auth.exchangeCodeForTokens(code).userId}"
}
