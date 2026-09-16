package com.rieltor.infrastructure.config

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class JsonSettingsStoreTest {
    @Test fun `exchange rates persist and must be positive finite numbers`() {
        val path = Files.createTempDirectory("rates-test").resolve("settings.json")
        JsonSettingsStore(path).use {
            assertEquals(45.0, it.snapshot().uahPerUsd)
            it.update { value -> value.copy(uahPerUsd = 46.0, usdPerEur = 1.2) }
            assertFails { it.update { value -> value.copy(uahPerUsd = 0.0) } }
            assertFails { it.update { value -> value.copy(usdPerEur = Double.NaN) } }
        }
        JsonSettingsStore(path).use {
            assertEquals(46.0, it.snapshot().uahPerUsd)
            assertEquals(1.2, it.snapshot().usdPerEur)
        }
    }
    @Test fun `quota and cooldown survive restart and duplicate reservation is idempotent`() {
        val path = Files.createTempDirectory("settings-test").resolve("settings.json")
        JsonSettingsStore(path, LocalSettings(minIntervalMs = 1000, maxMessagesPer24Hours = 1)).use {
            assertEquals(0, it.reserve("attempt", 1, 1000))
            assertEquals(0, it.reserve("attempt", 1, 1000))
            assertEquals(1, it.snapshot().slotReservations.size)
        }
        JsonSettingsStore(path).use {
            assertTrue(it.waitMillis(2000) > 0)
            assertFails { JsonSettingsStore(path) }
        }
        Files.writeString(path, "broken")
        assertFails { JsonSettingsStore(path) }
    }
}
