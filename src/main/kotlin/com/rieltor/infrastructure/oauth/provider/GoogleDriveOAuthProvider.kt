package com.rieltor.infrastructure.oauth.provider

import com.rieltor.application.port.OAuthProvider
import com.rieltor.infrastructure.google.GoogleDriveAuthService

class GoogleDriveOAuthProvider(private val auth: GoogleDriveAuthService) : OAuthProvider {
    override val id: String = "google"
    override val title: String = "Google Drive"
    override fun authorizeUrl(state: String): String = auth.buildAuthorizeUrl(state)
    override suspend fun exchangeCode(code: String): String? {
        auth.exchangeCodeForTokens(code)
        return null
    }
}
