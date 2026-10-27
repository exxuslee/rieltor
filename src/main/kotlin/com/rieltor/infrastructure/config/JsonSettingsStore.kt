package com.rieltor.infrastructure.config

import com.rieltor.domain.model.CatalogCodes
import com.rieltor.domain.model.RepostDestination
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

@Serializable
data class SlotReservation(val attemptId: String, val listingId: Long, val reservedAt: Long)

/** A Telegram chat and the forum topics to import from it. An empty list means the whole chat. */
@Serializable
data class MonitoredTelegramChat(
    val chatId: Long,
    val messageThreadIds: List<Long> = emptyList(),
    val name: String = "",
)

@Serializable
data class LocalSettings(
    val schemaVersion: Int = 1,
    val generation: Long = 0,
    val stabilityWindowMinutes: Long = 20,
    val topicTypeMapping: Map<String, String> = emptyMap(),
    val topicNames: Map<String, String> = emptyMap(),
    val uahPerUsd: Double = 45.0,
    val usdPerEur: Double = 1.1,
    val driveFileDelayMs: Long = 500,
    val driveJobDelayMin: Long = 1,
    val driveMaxAttempts: Int = 5,
    val driveRetryBaseMs: Long = 60_000,
    val maxCatalogPhotos: Int = 50,
    val minIntervalMs: Long = 20 * 60_000,
    val maxMessagesPer24Hours: Int = 36,
    val threadsMaxMessagesPer24Hours: Int = 10,
    val tiktokEnabled: Boolean = true,
    val threadsEnabled: Boolean = false,
    val blockedUntil: Long = 0,
    /** TikTok reservations; retain the legacy field and history when upgrading. Failed sends also count. */
    val slotReservations: List<SlotReservation> = emptyList(),
    val threadsSlotReservations: List<SlotReservation> = emptyList(),
    val serverPort: Int = 8383,
    val databasePath: String = "rieltor.db",
    val secretsPath: String = "secrets.json",
    val mediaDirectory: String = "media",
    val tikTokMode: String = "POST",
    val tikTokDailyLimitCooldownHours: Long = 8,
    val repostMaxPhotoCount: Int = 10,
    val repostContactPhone: String = "066-372-71-02",
    val telegramListingBotMaxPhotoCount: Int = 100,
    val monitoredTelegramChats: List<MonitoredTelegramChat> = emptyList(),
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
        now: Long, destination: RepostDestination = RepostDestination.TIKTOK,
    ): Long {
        check(!writeFailed) { "Settings storage unavailable" }
        val max = if (destination == RepostDestination.TIKTOK) state.maxMessagesPer24Hours else state.threadsMaxMessagesPer24Hours
        val interval = if (destination == RepostDestination.TIKTOK) state.minIntervalMs else 0
        val reservations = reservations(destination)
        val active = reservations.filter { it.reservedAt > now - REPOST_WINDOW_MS }
        val quota = if (active.size >= max) active.sortedBy { it.reservedAt }[active.size - max].reservedAt + REPOST_WINDOW_MS else 0
        val spacing = (reservations.maxOfOrNull { it.reservedAt } ?: -interval) + interval
        val blockedUntil = if (destination == RepostDestination.TIKTOK) state.blockedUntil else 0
        return (maxOf(blockedUntil, quota, spacing) - now).coerceAtLeast(0)
    }

    @Synchronized
    fun reserve(
        attemptId: String, listingId: Long, now: Long,
        destination: RepostDestination = RepostDestination.TIKTOK,
    ): Long {
        if (reservations(destination).any { it.attemptId == attemptId }) return 0
        val wait = waitMillis(now, destination)
        if (wait > 0) return wait
        val reservation = SlotReservation(attemptId, listingId, now)
        update {
            it.copy(
                slotReservations = retainedReservations(it.slotReservations, now) +
                    if (destination == RepostDestination.TIKTOK) listOf(reservation) else emptyList(),
                threadsSlotReservations = retainedReservations(it.threadsSlotReservations, now) +
                    if (destination == RepostDestination.THREADS) listOf(reservation) else emptyList(),
            )
        }
        return 0
    }

    private fun reservations(destination: RepostDestination) = when (destination) {
        RepostDestination.TIKTOK -> state.slotReservations
        RepostDestination.THREADS -> state.threadsSlotReservations
    }

    private fun retainedReservations(reservations: List<SlotReservation>, now: Long): List<SlotReservation> {
        // Keep the latest reservation even outside the daily window: TikTok's configured interval may be longer.
        val latest = reservations.maxOfOrNull { it.reservedAt }
        return reservations.filter { it.reservedAt > now - REPOST_WINDOW_MS || it.reservedAt == latest }
    }

    private fun validate(value: LocalSettings) {
        require(value.uahPerUsd.isFinite() && value.uahPerUsd > 0 && value.usdPerEur.isFinite() && value.usdPerEur > 0)
        require(value.schemaVersion == 1 && value.stabilityWindowMinutes >= 20)
        require(value.minIntervalMs >= 0 && value.maxMessagesPer24Hours > 0)
        require(value.threadsMaxMessagesPer24Hours > 0)
        require(value.driveMaxAttempts > 0 && value.driveRetryBaseMs > 0 && value.maxCatalogPhotos in 1..1000)
        require(value.driveFileDelayMs >= 0 && value.driveJobDelayMin >= 0)
        require(value.serverPort in 1..65535)
        require(value.databasePath.isNotBlank() && value.secretsPath.isNotBlank() && value.mediaDirectory.isNotBlank())
        require(value.tikTokMode.uppercase() in setOf("POST", "DRAFT"))
        require(value.tikTokDailyLimitCooldownHours in 1..48)
        require(value.repostMaxPhotoCount in 1..35)
        require(value.telegramListingBotMaxPhotoCount in 1..1000)
        require(value.monitoredTelegramChats.map { it.chatId }.distinct().size == value.monitoredTelegramChats.size)
        require(value.monitoredTelegramChats.all { chat ->
            chat.messageThreadIds.all { it > 0 } && chat.messageThreadIds.distinct().size == chat.messageThreadIds.size
        })
        require(value.topicTypeMapping.all { (key, type) -> key.matches(Regex("-?\\d+:\\d+")) && CatalogCodes.normalizeType(type) in CatalogCodes.types })
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

    private companion object {
        const val REPOST_WINDOW_MS = 86_400_000L
    }
}
