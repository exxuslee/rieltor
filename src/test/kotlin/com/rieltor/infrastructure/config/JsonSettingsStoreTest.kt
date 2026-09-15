package com.rieltor.infrastructure.config

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class JsonSettingsStoreTest {
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
