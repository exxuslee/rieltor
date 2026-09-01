package com.rieltor.infrastructure.database

import com.rieltor.domain.repository.TelegramRepostQueue
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.time.Clock
import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class TelegramHistoryCleanupJob(
    private val queue: TelegramRepostQueue,
    private val maxAge: Duration = Duration.ofDays(30),
    private val interval: Duration = Duration.ofHours(1),
    private val clock: Clock = Clock.systemUTC(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : Closeable {
    private val logger = LoggerFactory.getLogger(javaClass)
    private var job: Job? = null

    fun start() {
        check(job == null) { "Telegram history cleanup job is already started" }
        job = scope.launch {
            delay(60_000L.milliseconds)
            while (isActive) {
                runCatching { cleanNow() }.onFailure { logger.error("Telegram history cleanup failed", it) }
                delay(interval.toMillis().milliseconds)
            }
        }
    }

    internal fun cleanNow(): Int {
        val cutoff = clock.instant().minus(maxAge).epochSecond
        return queue.cleanHistoryBefore(cutoff).also { deleted ->
            if (deleted > 0) logger.info("Deleted {} Telegram history row(s) older than {}", deleted, maxAge)
        }
    }

    override fun close() {
        job?.cancel()
        scope.cancel()
    }
}
