package com.rieltor.application.service

import com.rieltor.application.port.RepostMasterLimiter
import com.rieltor.infrastructure.config.JsonSettingsStore
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

class CatalogRepostMasterLimiter(private val settings: JsonSettingsStore) : RepostMasterLimiter {
    override suspend fun awaitSlot() {
        while (true) {
            val wait = waitUntilMillis(System.currentTimeMillis())
            if (wait <= 0) return
            delay(wait.coerceAtMost(30_000).milliseconds)
        }
    }

    override fun waitUntilMillis(now: Long) = settings.waitMillis(now)
    fun reserve(attemptId: String, listingId: Long, now: Long) = settings.reserve(attemptId, listingId, now)
}
