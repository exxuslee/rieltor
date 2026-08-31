package com.rieltor.infrastructure.tiktok

import com.rieltor.domain.repository.TikTokPublishThrottleRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

class TikTokPublishDispatcher(
    private val repository: TikTokPublishThrottleRepository,
    private val maxPostsPer24Hours: Int,
    private val minPostIntervalMillis: Long,
    private val dailyLimitCooldownMillis: Long,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val mutex = Mutex()

    suspend fun awaitSlot() = mutex.withLock {
        require(maxPostsPer24Hours > 0) { "TikTok daily post limit must be positive." }
        require(minPostIntervalMillis >= 0) { "TikTok post interval must not be negative." }
        while (true) {
            val waitMillis = repository.reserveSlot(
                nowMillis(), WINDOW_MILLIS, maxPostsPer24Hours, minPostIntervalMillis,
            )
            if (waitMillis <= 0) return@withLock
            logger.info(
                "TikTok dispatcher is holding the next post. waitMinutes={}, maxPostsPer24Hours={}, minIntervalMinutes={}",
                (waitMillis + 59_999) / 60_000,
                maxPostsPer24Hours,
                minPostIntervalMillis / 60_000,
            )
            delayMillis(waitMillis)
        }
    }

    fun registerDailyLimit() {
        repository.blockUntil(nowMillis() + dailyLimitCooldownMillis)
        logger.warn(
            "TikTok daily creator limit reached; dispatcher paused all new posts for {} hours.",
            dailyLimitCooldownMillis / 3_600_000,
        )
    }

    companion object {
        const val WINDOW_MILLIS = 24 * 60 * 60 * 1_000L
    }
}
