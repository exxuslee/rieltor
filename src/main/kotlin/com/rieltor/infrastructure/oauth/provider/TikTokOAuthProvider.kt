package com.rieltor.infrastructure.oauth.provider

import com.rieltor.application.port.OAuthProvider
import com.rieltor.infrastructure.tiktok.TikTokAuthService

class TikTokOAuthProvider(private val auth: TikTokAuthService) : OAuthProvider {
    override val id: String = "tiktok"
    override val title: String = "TikTok"
    override fun authorizeUrl(state: String): String = auth.buildAuthorizeUrl(state)
    override suspend fun exchangeCode(code: String): String = "openId: ${auth.exchangeCodeForTokens(code).openId}"
}
