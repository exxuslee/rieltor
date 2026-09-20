package com.rieltor.application.worker

import com.rieltor.application.port.TelegramInboxSource
import com.rieltor.domain.model.CatalogPhoto
import com.rieltor.domain.model.SourcePhoto
import com.rieltor.domain.model.SourceRefresh
import com.rieltor.domain.model.TELEGRAM_PHOTO_VERSION
import com.rieltor.domain.usecase.NormalizeCatalogPriceUseCase
import com.rieltor.domain.usecase.PrepareCatalogListingUseCase
import com.rieltor.infrastructure.config.JsonSettingsStore
import com.rieltor.infrastructure.database.mapper.CatalogListingMapper
import com.rieltor.infrastructure.database.model.IncomingEntity
import com.rieltor.infrastructure.database.model.IncomingStatus
import com.rieltor.infrastructure.database.repository.CatalogRepository
import com.rieltor.infrastructure.google.GoogleDrivePhotoSource
import com.rieltor.infrastructure.media.LocalPublicMediaStorage
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes

class AdsWorker(
    private val source: TelegramInboxSource,
    private val repository: CatalogRepository,
    private val settings: JsonSettingsStore,
    private val drive: GoogleDrivePhotoSource,
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

    fun startWorkers() {
        if (!workersStarted.compareAndSet(false, true)) return
        scope.launch { loop(5) { verifyDue() } }
        scope.launch {
            loop(settings.snapshot().driveJobDelay.coerceAtLeast(1)) { downloadNext() }
        }
    }

    private suspend fun loop(pauseMinutes: Long, action: suspend () -> Unit) {
        while (currentCoroutineContext().isActive) {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logger.error("Catalog worker failed", error)
            }
            delay(pauseMinutes.minutes)
        }
    }

    suspend fun verify(row: IncomingEntity): Boolean {
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
                repository.stage(row, IncomingStatus.VerifyRetry, now(), now() + 60_000, failed = true)
                return false
            }
        }
        val current = repository.source(row.chatId, row.messageId) ?: return false
        return repository.revision(current) == repository.revision(row) &&
                current.status != IncomingStatus.Deleted && current.verifyAfter <= now()
    }

    suspend fun verifyDue() {
        val time = now()
        repository.incoming().filter {
            it.verifyAfter <= time
                    && it.nextAttemptAt <= time
                    && it.status in setOf(IncomingStatus.WaitingStability, IncomingStatus.VerifyRetry)
        }.forEach { row ->
            if (verify(row)) repository.stage(row, IncomingStatus.ReadyForMedia, now())
        }
    }

    suspend fun downloadNext(): Boolean {
        val row = repository.incoming().filter {
            it.status in setOf(
                IncomingStatus.ReadyForMedia,
                IncomingStatus.MediaRetry,
                IncomingStatus.MediaReady
            ) && it.nextAttemptAt <= now()
        }.maxWithOrNull(compareBy<IncomingEntity> { it.sourceCreatedAt }.thenBy { it.id }) ?: return false
        if (!verify(row)) return true
        val config = settings.snapshot()
        val topicKey = "${row.chatId}:${row.messageThreadId}"
        val mapper = CatalogListingMapper(
            PrepareCatalogListingUseCase(
                priceNormalizer = NormalizeCatalogPriceUseCase(config.uahPerUsd, config.usdPerEur)
            )
        )
        val parsed = mapper.fromIncoming(row, config.topicTypeMapping[topicKey], now()).let { listing ->
            val topic = config.topicNames[topicKey]
            listing.copy(
                tags = Json.encodeToString(
                    Json.decodeFromString<List<String>>(listing.tags) + listOfNotNull(topic)
                )
            )
        }
        if (parsed.status != "ACTIVE") {
            repository.discard(row)
            return true
        }
        if (!repository.claim(row, now())) return true
        try {
            val existing = (Json.decodeFromString<List<CatalogPhoto>>(row.mediaManifest) +
                    repository.cachedPhotos(parsed)).distinctBy { it.fileName }
            val photos = mutableListOf<CatalogPhoto>()
            fun record(photo: CatalogPhoto) {
                photos += photo
                check(repository.manifest(row, photos)) { "Telegram source changed during download" }
            }

            // The cover of a listing is the first photo of its Telegram post, so Telegram media
            // is downloaded first and Google Drive only fills the remaining slots.
            val telegramPhotos = Json.decodeFromString<List<SourcePhoto>>(row.sourcePhotos)
            for (photo in telegramPhotos.take(config.maxCatalogPhotos)) {
                val cached = existing.firstOrNull {
                    it.sourceFileId == photo.uniqueId && it.sourceVersion == TELEGRAM_PHOTO_VERSION &&
                            media.resolve(it.fileName) != null
                }
                if (cached != null) {
                    record(cached)
                    continue
                }
                val bytes = source.downloadPhoto(photo)
                // An unavailable Telegram photo is retried later: never publish a partial album.
                checkNotNull(bytes) { "Telegram photo ${photo.uniqueId} is unavailable" }
                record(
                    ByteArrayInputStream(bytes).use {
                        store("telegram-${photo.uniqueId}.jpg", it, photo.uniqueId, TELEGRAM_PHOTO_VERSION)
                    }
                )
            }

            val driveLinks = Json.decodeFromString<List<String>>(parsed.googleDriveUrls)
            val driveLimit = config.maxCatalogPhotos - photos.size
            if (driveLinks.isNotEmpty() && driveLimit > 0) {
                try {
                    drive.downloadCatalogPhotos(
                        driveLinks, driveLimit, config.driveFileDelayMs,
                        cached = { file ->
                            existing.any {
                                it.sourceFileId == file.id && it.sourceVersion == file.version && media.resolve(
                                    it.fileName
                                ) != null
                            }
                        }) { file, content ->
                        val cached = existing.firstOrNull {
                            it.sourceFileId == file.id && it.sourceVersion == file.version &&
                                    media.resolve(it.fileName) != null
                        }
                        val photo = cached ?: content.use {
                            store(file.name, it, file.id, file.version)
                        }
                        content.close()
                        record(photo)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    // Telegram already supplied the album; a broken Drive link cannot hide the post.
                    if (photos.isEmpty()) throw error
                    logger.warn(
                        "Google Drive photos unavailable; keeping Telegram photos. chatId={}, messageId={}, reason={}",
                        row.chatId, row.messageId, error.message ?: error.javaClass.simpleName,
                    )
                }
            }
            check(photos.isNotEmpty()) { "Listing has no Telegram or Google Drive photos" }
            if (!verify(row)) return true
            if (repository.promote(row, parsed.copy(photos = Json.encodeToString(photos)), now())) {
                photos.forEach { photo ->
                    media.resolve(photo.fileName)?.let {
                        Files.setLastModifiedTime(
                            it,
                            FileTime.fromMillis(parsed.sourceCreatedAt)
                        )
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val attempts = row.attemptCount + 1
            val delay =
                (config.driveRetryBaseMs * (1L shl attempts.coerceAtMost(10))).coerceAtMost(3_600_000) + Random.nextLong(
                    1000
                )
            if (attempts >= config.driveMaxAttempts) repository.discard(row)
            else repository.stage(row, IncomingStatus.MediaRetry, now(), now() + delay, failed = true)
        }
        return true
    }

    /** Stores one downloaded image and describes it for the catalog manifest. */
    private fun store(name: String, content: InputStream, sourceId: String, version: String): CatalogPhoto {
        val stored = media.store(name, content, null)
        val path = Path.of(stored.localPath)
        val image = ImageIO.read(path.toFile())
        val hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
            .joinToString("") { byte -> "%02x".format(byte) }
        return CatalogPhoto(path.fileName.toString(), sourceId, version, image.width, image.height, hash)
    }

    override fun close() {
        runBlocking { scope.coroutineContext[Job.Key]?.cancelAndJoin() }; source.close()
    }
}