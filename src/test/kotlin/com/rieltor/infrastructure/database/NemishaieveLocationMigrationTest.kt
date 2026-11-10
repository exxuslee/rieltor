package com.rieltor.infrastructure.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.rieltor.application.service.CatalogFilterParser
import com.rieltor.domain.model.CatalogCodes
import com.rieltor.domain.model.ListingStatus
import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.model.AdEntity
import com.rieltor.infrastructure.database.repository.CatalogRepository
import java.nio.file.Files
import kotlin.test.*

class NemishaieveLocationMigrationTest {
    @Test fun `migrates existing other locations and preserves publication data`() {
        val path = Files.createTempDirectory("nemishaieve-location").resolve("test.db")
        val original = AdEntity(
            id = 4579, adId = "existing-ad", chatId = -1, messageId = 4579, messageThreadId = 20,
            sourceRevision = "revision", title = "Продам квартиру", price = 80000, currency = "USD",
            timestamp = 1000, status = ListingStatus.Active, location = "OTHER",
            rawText = "Немішаєве\nКвартира\nЦіна 80000 USD", repostTikTok = "PUBLISHED", repeats = 3,
        )
        val horenka = original.copy(id = 1431, adId = "horenka", messageId = 1431,
            rawText = "с. Горенка\nБудинок\nЦіна 80000 USD")
        val unchanged = listOf(
            original.copy(id = 2, adId = "unknown", messageId = 2, rawText = "Невідоме селище"),
            original.copy(id = 3, adId = "ambiguous", messageId = 3, rawText = "Немішаєве або Буча"),
            original.copy(id = 4, adId = "known", messageId = 4, location = "BUCHA"),
        )
        RoomDatabaseStore(path).use { db ->
            val repo = CatalogRepository(db)
            (unchanged + original + horenka).forEach(repo::save)
        }
        BundledSQLiteDriver().open(path.toString()).use { it.execSQL("PRAGMA user_version=38") }
        repeat(2) {
            RoomDatabaseStore(path).use { db ->
                val repo = CatalogRepository(db)
                assertEquals(original.copy(location = "NEMISHAIEVE"), repo.listing(original.id))
                assertEquals(horenka.copy(location = "HORENKA"), repo.listing(horenka.id))
                unchanged.forEach { assertEquals(it, repo.listing(it.id)) }
            }
        }
        assertEquals("Немішаєве", CatalogCodes.locationName("NEMISHAIEVE"))
        assertEquals("Горенка", CatalogCodes.locationName("HORENKA"))
        assertEquals(listOf("NEMISHAIEVE", "HORENKA"), CatalogFilterParser().parse(
            mapOf("location" to listOf("NEMISHAIEVE,HORENKA")),
        ).locations)
    }
}
