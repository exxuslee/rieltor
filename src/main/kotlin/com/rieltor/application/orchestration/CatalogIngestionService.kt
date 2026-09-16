package com.rieltor.application.orchestration

import com.rieltor.application.port.TelegramInboxSource
import com.rieltor.domain.model.CatalogPhoto
import com.rieltor.domain.model.SourceRefresh
import com.rieltor.domain.service.CatalogListingParser
import com.rieltor.infrastructure.config.JsonSettingsStore
import com.rieltor.infrastructure.database.model.IncomingEntity
import com.rieltor.infrastructure.database.repository.CatalogRepository
import com.rieltor.infrastructure.google.GoogleDrivePhotoSource
import com.rieltor.infrastructure.media.LocalPublicMediaStorage
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO

class CatalogIngestionService(
    private val source: TelegramInboxSource, private val repository: CatalogRepository,
    private val settings: JsonSettingsStore, private val drive: GoogleDrivePhotoSource,
    private val media: LocalPublicMediaStorage,
    private val now: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logger = LoggerFactory.getLogger(javaClass)

    private val workersStarted = AtomicBoolean(false)

    /** Starts only the TDLib Telegram session so it can authorize before background work begins. */
    fun startTelegramSession() {
        source.start()
    }

    fun start() {
        startTelegramSession()
        startWorkers()
    }

    fun startWorkers() {
        if (!workersStarted.compareAndSet(false, true)) return
        scope.launch { loop(5_000) { verifyDue() } }
        scope.launch { loop(settings.snapshot().driveJobDelayMs.coerceAtLeast(1_000)) { downloadNext() } }
    }

    private suspend fun loop(pause: Long, action: suspend () -> Unit) {
        while (currentCoroutineContext().isActive) {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logger.error("Catalog worker failed", error)
            }
            delay(pause)
        }
    }

    suspend fun verify(rows: List<IncomingEntity>): Boolean {
        for (row in rows) {
            when (val refreshed = source.refresh(row.chatId, requireNotNull(row.messageId))) {
                is SourceRefresh.Found -> repository.receive(
                    refreshed.message,
                    now(),
                    settings.snapshot().stabilityWindowMinutes * 60_000
                )

                SourceRefresh.Deleted -> {
                    repository.delete(row.chatId, row.messageId, now()); return false
                }

                SourceRefresh.Unavailable -> {
                    repository.stage(rows, "VERIFY_RETRY", now(), now() + 60_000, failed = true)
                    return false
                }
            }
        }
        val current = repository.group(rows.first().groupKey)
        return repository.revision(current) == repository.revision(rows) && current.all { it.status != "DELETED" && it.verifyAfter <= now() }
    }

    suspend fun verifyDue() {
        val time = now()
        repository.groups().filter { group ->
            group.all { it.verifyAfter <= time && it.nextAttemptAt <= time } &&
                    group.any {
                        it.status in setOf(
                            "WAITING_STABILITY",
                            "VERIFY_RETRY"
                        ) || it.status == "PROMOTED" && (it.verifiedAt ?: 0) < time - 20 * 60_000
                    }
        }.forEach { rows ->
            if (verify(rows)) repository.stage(
                rows,
                if (rows.all { it.status == "PROMOTED" }) "PROMOTED" else "READY_FOR_MEDIA",
                now()
            )
        }
    }

    suspend fun downloadNext(): Boolean {
        val rows = repository.groups().filter { group ->
            group.all {
                it.status in setOf("READY_FOR_MEDIA", "MEDIA_RETRY", "MEDIA_READY") && it.nextAttemptAt <= now()
            }
        }.maxWithOrNull(compareBy<List<IncomingEntity>> { it.first().sourceCreatedAt }.thenBy { it.first().id })
            ?: return false
        if (!verify(rows)) return true
        val config = settings.snapshot()
        val topicKey = "${rows.first().chatId}:${rows.first().messageThreadId}"
        val parser = CatalogListingParser(
            priceNormalizer = com.rieltor.domain.service.CatalogPriceNormalizer(
                config.uahPerUsd,
                config.usdPerEur
            )
        )
        val parsed = parser.parse(rows, config.topicTypeMapping[topicKey], now()).let { listing ->
            val topic = config.topicNames[topicKey]
            listing.copy(
                tags = Json.encodeToString(
                    Json.decodeFromString<List<String>>(listing.tags) + listOfNotNull(topic)
                )
            )
        }
        if (parsed.status != "ACTIVE") {
            repository.discard(rows)
            return true
        }
        val token = repository.claim(rows, now()) ?: return true
        try {
            val existing = (Json.decodeFromString<List<CatalogPhoto>>(rows.first().mediaManifest) +
                    repository.cachedPhotos(parsed)).distinctBy { it.fileName }
            val photos = mutableListOf<CatalogPhoto>()
            drive.downloadCatalogPhotos(
                Json.decodeFromString(parsed.googleDriveUrls), config.maxCatalogPhotos, config.driveFileDelayMs,
                cached = { file ->
                    existing.any {
                        it.sourceFileId == file.id && it.sourceVersion == file.version && media.resolve(
                            it.fileName
                        ) != null
                    }
                }) { file, content ->
                val cached = existing.firstOrNull {
                    it.sourceFileId == file.id && it.sourceVersion == file.version && media.resolve(it.fileName) != null
                }
                val photo = cached ?: content.use {
                    val stored = media.store(file.name, it, null)
                    val path = java.nio.file.Path.of(stored.localPath)
                    val image = ImageIO.read(path.toFile())
                    val hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
                        .joinToString("") { byte -> "%02x".format(byte) }
                    CatalogPhoto(path.fileName.toString(), file.id, file.version, image.width, image.height, hash)
                }
                content.close()
                photos += photo
                check(repository.manifest(rows, token, photos, now())) { "Telegram source changed during download" }
            }
            check(photos.isNotEmpty()) { "No Google Drive photos" }
            if (!verify(rows)) return true
            if (repository.promote(rows, token, parsed.copy(photos = Json.encodeToString(photos)), now())) {
                photos.forEach { photo ->
                    media.resolve(photo.fileName)?.let {
                        Files.setLastModifiedTime(
                            it,
                            java.nio.file.attribute.FileTime.fromMillis(parsed.sourceCreatedAt)
                        )
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val attempts = rows.first().attemptCount + 1
            val delay =
                (config.driveRetryBaseMs * (1L shl attempts.coerceAtMost(10))).coerceAtMost(3_600_000) + kotlin.random.Random.nextLong(
                    1000
                )
            if (attempts >= config.driveMaxAttempts) repository.discard(rows)
            else repository.stage(rows, "MEDIA_RETRY", now(), now() + delay, failed = true)
        }
        return true
    }

    override fun close() {
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }; source.close()
    }
}
