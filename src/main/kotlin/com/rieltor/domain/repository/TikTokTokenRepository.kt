package com.rieltor.domain.repository

import com.rieltor.domain.model.StoredTokens

interface TikTokTokenRepository {
    fun save(tokens: StoredTokens)
    fun find(openId: String): StoredTokens?
    fun latest(): StoredTokens?
}