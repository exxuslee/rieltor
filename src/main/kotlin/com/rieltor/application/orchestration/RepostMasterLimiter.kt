package com.rieltor.application.orchestration

import com.rieltor.domain.repository.TikTokPublishThrottleRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

fun interface RepostMasterLimiter {
    suspend fun awaitSlot()

    /** Current limiter wait, if a FIFO head is being held by this limiter. */
    fun waitUntilMillis(): Long? = null
}

class PersistentRepostMasterLimiter(
    private val repository: TikTokPublishThrottleRepository,
    private val maxMessagesPer24Hours: Int,
    private val minIntervalMillis: Long,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
) : RepostMasterLimiter {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val mutex = Mutex()
    private val waitUntilMillis = AtomicLong(0L)

    override suspend fun awaitSlot() = mutex.withLock {
        require(maxMessagesPer24Hours > 0) { "Master daily repost limit must be positive." }
        require(minIntervalMillis >= 0) { "Master repost interval must not be negative." }
        while (true) {
            val waitMillis = repository.reserveSlot(
                nowMillis(), WINDOW_MILLIS, maxMessagesPer24Hours, minIntervalMillis,
            )
            if (waitMillis <= 0) {
                waitUntilMillis.set(0L)
                return@withLock
            }
            waitUntilMillis.set(nowMillis() + waitMillis)
            logger.info(
                "Master repost limiter is holding the FIFO head. waitMinutes={}, maxMessagesPer24Hours={}, minIntervalMinutes={}",
                (waitMillis + 59_999) / 60_000,
                maxMessagesPer24Hours,
                minIntervalMillis / 60_000,
            )
            delayMillis(waitMillis)
        }
    }

    override fun waitUntilMillis(): Long? = waitUntilMillis.get().takeIf { it > 0L }

    companion object {
        const val WINDOW_MILLIS = 24 * 60 * 60 * 1_000L
    }
}
