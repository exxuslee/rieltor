package com.rieltor.infrastructure.media

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class MediaCleanupJob(
    private val directory: Path,
    private val maxAge: Duration = Duration.ofDays(1),
    private val interval: Duration = Duration.ofHours(1),
    private val clock: Clock = Clock.systemUTC(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : Closeable {
    private val logger = LoggerFactory.getLogger(MediaCleanupJob::class.java)
    private var job: Job? = null

    fun start() {
        check(job == null) { "Media cleanup job is already started" }
        job = scope.launch {
            delay(60_000L.milliseconds)
            while (isActive) {
                runCatching { cleanNow() }.onFailure { logger.error("Media cleanup failed", it) }
                delay(interval.toMillis().milliseconds)
            }
        }
    }

    internal fun cleanNow(): Int {
        if (!Files.isDirectory(directory)) return 0

        val cutoff = clock.instant().minus(maxAge)
        var deletedCount = 0
        Files.list(directory).use { paths ->
            paths
                .filter(Files::isRegularFile)
                .filter(::isSupportedImage)
                .filter { Files.getLastModifiedTime(it).toInstant().isBefore(cutoff) }
                .forEach { path ->
                    if (Files.deleteIfExists(path)) deletedCount++
                }
        }
        if (deletedCount > 0) {
            logger.info("Deleted {} media file(s) older than {}", deletedCount, maxAge)
        }
        return deletedCount
    }

    override fun close() {
        job?.cancel()
        scope.cancel()
    }

    private fun isSupportedImage(path: Path): Boolean =
        path.fileName.toString().substringAfterLast('.', "").lowercase() in SUPPORTED_EXTENSIONS

    private companion object {
        val SUPPORTED_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    }
}
