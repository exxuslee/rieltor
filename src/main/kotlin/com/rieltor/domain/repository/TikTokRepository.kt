package com.rieltor.domain.repository

/** Global cooldown reported by the TikTok API. */
interface TikTokRepository {
    fun blockUntil(blockedUntilMillis: Long)
}
