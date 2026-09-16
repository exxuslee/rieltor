package com.rieltor.infrastructure.config

import com.rieltor.domain.model.CatalogCodes
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.*

@Serializable
data class SlotReservation(val attemptId: String, val listingId: Long, val reservedAt: Long)

@Serializable
data class LocalSettings(
    val schemaVersion: Int = 1, val generation: Long = 0,
    val stabilityWindowMinutes: Long = 20,
    val topicTypeMapping: Map<String, String> = emptyMap(),
    val topicNames: Map<String, String> = emptyMap(),
    val uahPerUsd: Double = 45.0, val usdPerEur: Double = 1.1,
    val driveFileDelayMs: Long = 500, val driveJobDelayMs: Long = 1_000,
    val driveMaxAttempts: Int = 5, val driveRetryBaseMs: Long = 60_000, val maxCatalogPhotos: Int = 50,
    val minIntervalMs: Long = 20 * 60_000, val maxMessagesPer24Hours: Int = 36,
    val tiktokEnabled: Boolean = true, val threadsEnabled: Boolean = false, val blockedUntil: Long = 0,
    val slotReservations: List<SlotReservation> = emptyList(),
    val orphanGraceMs: Long = 4 * 86_400_000L,
)

/** The process lock covers JSON state and workers sharing its database. */
class JsonSettingsStore(val path: Path, defaults: LocalSettings = LocalSettings()) : AutoCloseable {
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    private val lockChannel: FileChannel
    private val processLock: java.nio.channels.FileLock
    private var state: LocalSettings
    private var writeFailed = false

    init {
        path.toAbsolutePath().parent.let(Files::createDirectories)
        lockChannel = FileChannel.open(
            path.resolveSibling("${path.fileName}.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE
        )
        try {
            processLock = lockChannel.tryLock() ?: error("settings.json is owned by another process")
            state = if (Files.exists(path)) json.decodeFromString(Files.readString(path)) else defaults
            validate(state)
            if (!Files.exists(path)) persist(state)
        } catch (error: Throwable) {
            lockChannel.close(); throw error
        }
    }

    @Synchronized
    fun snapshot(): LocalSettings = state
    @Synchronized
    fun update(change: (LocalSettings) -> LocalSettings) {
        check(!writeFailed) { "Settings persistence failed; restart after restoring settings.json" }
        val next = change(state).copy(generation = state.generation + 1)
        validate(next)
        try {
            persist(next); state = next
        } catch (error: Throwable) {
            writeFailed = true; throw error
        }
    }

    @Synchronized
    fun waitMillis(
        now: Long, window: Long = 86_400_000L,
        max: Int = state.maxMessagesPer24Hours, interval: Long = state.minIntervalMs
    ): Long {
        check(!writeFailed) { "Settings storage unavailable" }
        val active = state.slotReservations.filter { it.reservedAt > now - window }.sortedBy { it.reservedAt }
        val quota = if (active.size >= max) active[active.size - max].reservedAt + window else 0
        val spacing = (state.slotReservations.maxOfOrNull { it.reservedAt } ?: -interval) + interval
        return (maxOf(state.blockedUntil, quota, spacing) - now).coerceAtLeast(0)
    }

    @Synchronized
    fun reserve(
        attemptId: String, listingId: Long, now: Long,
        window: Long = 86_400_000L, max: Int = state.maxMessagesPer24Hours,
        interval: Long = state.minIntervalMs
    ): Long {
        if (state.slotReservations.any { it.attemptId == attemptId }) return 0
        val wait = waitMillis(now, window, max, interval)
        if (wait > 0) return wait
        update { it.copy(slotReservations = it.slotReservations + SlotReservation(attemptId, listingId, now)) }
        return 0
    }

    fun reserveAnonymous(now: Long, window: Long, max: Int, interval: Long) =
        reserve(UUID.randomUUID().toString(), 0, now, window, max, interval)

    private fun validate(value: LocalSettings) {
        require(value.uahPerUsd.isFinite() && value.uahPerUsd > 0 && value.usdPerEur.isFinite() && value.usdPerEur > 0)
        require(value.schemaVersion == 1 && value.stabilityWindowMinutes >= 20)
        require(value.minIntervalMs >= 0 && value.maxMessagesPer24Hours > 0)
        require(value.driveMaxAttempts > 0 && value.driveRetryBaseMs > 0 && value.maxCatalogPhotos in 1..1000)
        require(value.driveFileDelayMs >= 0 && value.driveJobDelayMs >= 0 && value.orphanGraceMs > 0)
        require(value.topicTypeMapping.all { (key, type) -> key.matches(Regex("-?\\d+:\\d+")) && type in CatalogCodes.types })
    }

    private fun persist(value: LocalSettings) {
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        val bytes = json.encodeToString(value).toByteArray()
        FileChannel.open(
            temporary,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        ).use {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) it.write(buffer)
            it.force(true)
        }
        if (Files.exists(path)) Files.copy(
            path,
            path.resolveSibling("${path.fileName}.bak"),
            StandardCopyOption.REPLACE_EXISTING
        )
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun close() {
        processLock.release(); lockChannel.close()
    }
}
