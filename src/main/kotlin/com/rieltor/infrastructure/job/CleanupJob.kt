package com.rieltor.infrastructure.job

import com.rieltor.infrastructure.database.repository.CatalogRepository
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class CleanupJob(
    private val directory: Path,
    private val maxAge: Duration = Duration.ofDays(30),
    private val interval: Duration = Duration.ofDays(1),
    private val clock: Clock = Clock.systemUTC(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val referencedFiles: () -> Set<String> = { emptySet() },
    private val catalogRepository: CatalogRepository? = null,
    private val orphanGrace: Duration = Duration.ofHours(1),
) : Closeable {
    private val logger = LoggerFactory.getLogger(CleanupJob::class.java)
    private var job: Job? = null

    fun start() {
        check(job == null) { "Media cleanup job is already started" }
        job = scope.launch {
            while (isActive) {
                runCatching { cleanNow() }.onFailure { logger.error("Media cleanup failed", it) }
                delay(interval.toMillis().milliseconds)
            }
        }
    }

    internal fun cleanNow(): Int {
        val cutoff = clock.instant().minus(maxAge)
        return catalogRepository?.withMediaCleanup(cutoff.toEpochMilli()) { cleanFiles(it) }
            ?: cleanFiles(null)
    }

    private fun cleanFiles(retention: CatalogRepository.MediaRetention?): Int {
        if (!Files.isDirectory(directory)) return 0
        val protectedFiles = retention?.referenced ?: referencedFiles()
        // Fresh unreferenced files may still be downloading or serving a social publication.
        val orphanCutoff = clock.instant().minus(if (retention != null) orphanGrace else maxAge)
        var deletedCount = 0
        Files.list(directory).use { paths ->
            paths
                .filter(Files::isRegularFile)
                .filter(::isSupportedImage)
                .filter { it.fileName.toString() !in protectedFiles }
                .filter {
                    it.fileName.toString() in retention?.removed.orEmpty() ||
                            Files.getLastModifiedTime(it).toInstant().isBefore(orphanCutoff)
                }
                .forEach { path ->
                    if (Files.deleteIfExists(path)) deletedCount++
                }
        }
        if (deletedCount > 0) {
            logger.info("Deleted {} expired or unreferenced media file(s)", deletedCount)
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