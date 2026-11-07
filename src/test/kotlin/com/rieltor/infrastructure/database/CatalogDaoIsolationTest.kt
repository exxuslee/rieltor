package com.rieltor.infrastructure.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.rieltor.application.service.CatalogQueryService
import com.rieltor.domain.model.ListingStatus
import com.rieltor.domain.model.RepostDestination
import com.rieltor.domain.model.RepostStatus
import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.model.AdEntity
import com.rieltor.infrastructure.database.repository.CatalogRepository
import com.rieltor.web.api.CatalogListingApi
import io.ktor.http.*
import java.nio.file.Files
import kotlin.test.*

class CatalogDaoIsolationTest {
    private fun ad() = AdEntity(
        chatId = -1002681732909L, messageId = 1, adId = "ad", messageThreadId = 0,
        sourceRevision = "revision", timestamp = 100,
        title = "House", status = ListingStatus.Active, price = 80000, currency = "USD",
    )

    @Test fun `catalog searches all raw text words ignoring Cyrillic case and combines filters`() {
        val path = Files.createTempDirectory("catalog-search").resolve("test.db")
        RoomDatabaseStore(path).use { db ->
            val repo = CatalogRepository(db)
            repo.save(ad().copy(rawText = "ІРПІНЬ: ТЕРАСА та паркінг, знижка 5%_!"))
            val api = CatalogListingApi("https://example.test", CatalogQueryService(repo))
            fun count(query: String, max: String = "90000") = api.list(parametersOf(
                "query" to listOf(query), "priceMax" to listOf(max),
            )).items.size
            assertEquals(1, count("  ПАРКІНГ   ірпінь тераса  "))
            assertEquals(0, count("тераса басейн"))
            assertEquals(0, count("House"))
            assertEquals(0, count("тераса", "70000"))
            assertEquals(1, count("5%_"))
            assertEquals(0, count("' OR 1=1 --"))
            assertEquals(1, count("   "))
        }
    }

    @Test fun `catalog hides abbreviated internal costs from already imported descriptions`() {
        val path = Files.createTempDirectory("catalog-public-description").resolve("test.db")
        RoomDatabaseStore(path).use { db ->
            val repo = CatalogRepository(db)
            val id = repo.save(ad().copy(
                rawText = "Квартира в Ірпені\nМеблі та техніка\n5%/2\n2% Оф.\nЦіна 80000$",
                description = "Меблі та техніка\n5%/2\n2% Оф.",
            ))
            val description = CatalogListingApi("https://example.test", CatalogQueryService(repo)).one(id)?.description
            assertNotNull(description)
            assertContains(description, "Меблі та техніка")
            assertFalse(description.contains("5%/2"))
            assertFalse(description.contains("2% Оф."))
        }
    }

    @Test fun `catalog reads work without the repost table`() {
        val path = Files.createTempDirectory("catalog-isolation").resolve("test.db")
        RoomDatabaseStore(path).use { db ->
            val repo = CatalogRepository(db)
            val id = repo.save(ad())
            // Removed repost tables are unnecessary for catalog reads.
            BundledSQLiteDriver().open(path.toString()).use { it.execSQL("DROP TABLE IF EXISTS repostTab") }
            assertEquals(ad().copy(id = id), repo.listing(id))
            assertEquals(id, repo.listings().single().id)
            assertTrue(repo.cachedPhotos(ad()).isEmpty())
            db.blocking { room ->
                val dao = room.catalogDao()
                assertEquals(id, dao.listingForSource(-1002681732909L, 1)?.id)
                assertEquals(id, dao.promotionMatches(-1002681732909L, 1, "ad").single().id)
                assertTrue(dao.isActive(id))
            }
            val api = CatalogListingApi("https://example.test", CatalogQueryService(repo))
            assertNotNull(api.one(id))
            assertEquals(1, api.list(Parameters.Empty).items.size)
            assertTrue(repo.cleanExpired(0).removed.isEmpty())
        }
    }

    @Test fun contentUpdatesPreserveConcurrentRepostStatusChanges() {
        RoomDatabaseStore(Files.createTempDirectory("repost-edit").resolve("test.db")).use { db ->
            val repo = CatalogRepository(db)
            val id = repo.save(ad())
            val stale = assertNotNull(repo.listing(id))
            assertTrue(repo.beginRepost(id, RepostDestination.TIKTOK))
            repo.finishRepost(id, RepostDestination.TIKTOK, RepostStatus.Published, 200)
            repo.save(stale.copy(title = "Edited"))
            val updated = assertNotNull(repo.listing(id))
            assertEquals("Edited", updated.title)
            assertEquals("PUBLISHED", updated.repostTikTok)
            assertEquals("PENDING", updated.repostThreads)
            assertNull(repo.nextRepost(true, false))
        }
    }
}
