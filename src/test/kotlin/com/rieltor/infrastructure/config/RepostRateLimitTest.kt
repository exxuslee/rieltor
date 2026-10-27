package com.rieltor.infrastructure.config

import com.rieltor.domain.model.RepostDestination
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RepostRateLimitTest {
    @Test fun `tiktok block prevents its reservations until it expires`() {
        JsonSettingsStore(Files.createTempDirectory("limiter-block").resolve("settings.json")).use { settings ->
            settings.update { it.copy(blockedUntil = 86_401_000, minIntervalMs = 0) }
            assertEquals(86_400_000L, settings.waitMillis(1000))
            assertEquals(86_400_000L, settings.reserve("blocked", 1, 1000))
            assertTrue(settings.snapshot().slotReservations.isEmpty())
            assertEquals(0L, settings.reserve("allowed", 1, 86_401_000))
            assertEquals(1, settings.snapshot().slotReservations.size)
        }
    }
    @Test fun `threads quota survives restart independently of legacy tiktok state`() {
        val path = Files.createTempDirectory("threads-limiter-restart").resolve("settings.json")
        Files.writeString(path, """{"blockedUntil":86401000,"minIntervalMs":0,"slotReservations":[{"attemptId":"legacy","listingId":1,"reservedAt":1000}]}""")
        JsonSettingsStore(path).use { settings ->
            assertEquals(10, settings.snapshot().threadsMaxMessagesPer24Hours)
            repeat(10) { index ->
                assertEquals(0L, settings.reserve("threads-$index", index + 1L, 1000, RepostDestination.THREADS))
            }
            assertEquals(0L, settings.reserve("threads-0", 1, 1000, RepostDestination.THREADS))
            assertEquals(86_400_000L, settings.reserve("eleventh", 11, 1000, RepostDestination.THREADS))
            assertEquals(1, settings.snapshot().slotReservations.size)
            assertEquals("legacy", settings.snapshot().slotReservations.single().attemptId)
            assertEquals(86_400_000L, settings.waitMillis(1000))
        }
        JsonSettingsStore(path).use { settings ->
            assertEquals(10, settings.snapshot().threadsSlotReservations.size)
            assertEquals(1L, settings.waitMillis(86_400_999, RepostDestination.THREADS))
            assertEquals(0L, settings.reserve("eleventh", 11, 86_401_000, RepostDestination.THREADS))
        }
    }
    @Test fun `spacing and rolling quota survive restart without duplicate reservations`() {
        val path = Files.createTempDirectory("limiter-restart").resolve("settings.json")
        JsonSettingsStore(path).use { settings ->
            settings.update { it.copy(minIntervalMs = 10, maxMessagesPer24Hours = 2) }
            assertEquals(0L, settings.reserve("first", 1, 0))
            assertEquals(10L, settings.reserve("second", 2, 0))
            assertEquals(0L, settings.reserve("second", 2, 10))
            assertEquals(0L, settings.reserve("second", 2, 10))
            assertEquals(2, settings.snapshot().slotReservations.size)
        }
        JsonSettingsStore(path).use { settings ->
            assertEquals(86_399_990L, settings.waitMillis(10))
            assertEquals(86_399_990L, settings.reserve("third", 3, 10))
            assertEquals(0L, settings.reserve("third", 3, 86_400_000))
        }
    }

    @Test fun `expired reservations are pruned without losing a long tiktok interval`() {
        val day = 86_400_000L
        val path = Files.createTempDirectory("limiter-pruning").resolve("settings.json")
        val history = (1L..100L).map { SlotReservation("old-$it", it, it * day) }
        val now = 101 * day + 1
        JsonSettingsStore(path, LocalSettings(
            minIntervalMs = 2 * day, slotReservations = history, threadsSlotReservations = history,
        )).use { settings ->
            assertEquals(day - 1, settings.waitMillis(now))
            assertEquals(0L, settings.reserve("new-threads", 101, now, RepostDestination.THREADS))
            assertEquals(listOf(history.last()), settings.snapshot().slotReservations)
            assertEquals(2, settings.snapshot().threadsSlotReservations.size)
            assertEquals(day - 1, settings.waitMillis(now))
            assertEquals(0L, settings.reserve("new-threads", 101, now, RepostDestination.THREADS))
            assertEquals(2, settings.snapshot().threadsSlotReservations.size)
        }
        JsonSettingsStore(path).use { settings ->
            assertEquals(day - 1, settings.waitMillis(now))
            assertEquals(0L, settings.reserve("new-tiktok", 101, 102 * day))
        }
    }

    @Test fun `quota release uses reservation times even when legacy history is unordered`() {
        val path = Files.createTempDirectory("limiter-unordered").resolve("settings.json")
        JsonSettingsStore(path, LocalSettings(
            minIntervalMs = 0, maxMessagesPer24Hours = 2,
            slotReservations = listOf(SlotReservation("newer", 1, 20), SlotReservation("older", 2, 10)),
        )).use { settings ->
            assertEquals(86_399_980L, settings.waitMillis(30))
            assertEquals(0L, settings.reserve("next", 3, 86_400_010))
        }
    }
}
