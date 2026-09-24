package com.rieltor.application.orchestration
import com.rieltor.application.service.CatalogRepostMasterLimiter
import com.rieltor.infrastructure.config.JsonSettingsStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RepostMasterLimiterTest {
    @Test fun `shared block prevents reservations until it expires`() {
        JsonSettingsStore(Files.createTempDirectory("limiter-block").resolve("settings.json")).use { settings ->
            settings.update { it.copy(blockedUntil = 86_401_000, minIntervalMs = 0) }
            val limiter = CatalogRepostMasterLimiter(settings)
            assertEquals(86_400_000L, limiter.waitUntilMillis(1000))
            assertEquals(86_400_000L, limiter.reserve("blocked", 1, 1000))
            assertTrue(settings.snapshot().slotReservations.isEmpty())
            assertEquals(0L, limiter.reserve("allowed", 1, 86_401_000))
            assertEquals(1, settings.snapshot().slotReservations.size)
        }
    }
    @Test fun `spacing and rolling quota survive restart without duplicate reservations`() {
        val path = Files.createTempDirectory("limiter-restart").resolve("settings.json")
        JsonSettingsStore(path).use { settings ->
            settings.update { it.copy(minIntervalMs = 10, maxMessagesPer24Hours = 2) }
            val limiter = CatalogRepostMasterLimiter(settings)
            assertEquals(0L, limiter.reserve("first", 1, 0))
            assertEquals(10L, limiter.reserve("second", 2, 0))
            assertEquals(0L, limiter.reserve("second", 2, 10))
            assertEquals(0L, limiter.reserve("second", 2, 10))
            assertEquals(2, settings.snapshot().slotReservations.size)
        }
        JsonSettingsStore(path).use { settings ->
            val limiter = CatalogRepostMasterLimiter(settings)
            assertEquals(86_399_990L, limiter.waitUntilMillis(10))
            assertEquals(86_399_990L, limiter.reserve("third", 3, 10))
            assertEquals(0L, limiter.reserve("third", 3, 86_400_000))
        }
    }
}
