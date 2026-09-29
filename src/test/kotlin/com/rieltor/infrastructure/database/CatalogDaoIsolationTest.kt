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
import com.rieltor.infrastructure.database.repository.TikTokRepositoryImpl
import com.rieltor.web.api.CatalogListingApi
import io.ktor.http.*
import java.nio.file.Files
import kotlin.test.*

class CatalogDaoIsolationTest {
    @Test fun `pending shares expire by creation time without losing history after restart`() {
        val path = Files.createTempDirectory("pending-share-expiry").resolve("test.db")
        val window = 86_400_000L
        val createdAt = 1000L
        val cutoff = createdAt + window
        RoomDatabaseStore(path).use { db ->
            val catalog = CatalogRepository(db)
            val tiktok = TikTokRepositoryImpl(db, catalog)
            for (n in 1L..6L) {
                val id = catalog.save(ad().copy(adId = "ad$n", messageId = n))
                val attempt = assertNotNull(catalog.prepare(id, setOf(RepostDestination.TIKTOK), createdAt))
                tiktok.trackPublishForListing(id, attempt, "draft-$n", "DRAFT", createdAt)
                tiktok.updateTrackedStatus("draft-$n", "SEND_TO_USER_INBOX", cutoff - 1)
            }
            assertEquals(6, tiktok.trackedPublishes(cutoff - 1, window).size)
            assertTrue(tiktok.trackedPublishes(cutoff, window).isEmpty())
        }
        RoomDatabaseStore(path).use { db ->
            val catalog = CatalogRepository(db)
            val tiktok = TikTokRepositoryImpl(db, catalog)
            assertTrue(tiktok.trackedPublishes(cutoff + 1, window).isEmpty())
            assertEquals(6, catalog.reposts().size)
            catalog.reposts().forEach {
                assertEquals(RepostStatus.DeliveredDraft, catalog.status(it, RepostDestination.TIKTOK))
                assertNotNull(catalog.publication(it, RepostDestination.TIKTOK).attempts.single().publishId)
            }
            assertNull(catalog.nextRepost(true, false))
            val id = catalog.save(ad().copy(adId = "fresh", messageId = 7))
            val attempt = assertNotNull(catalog.prepare(id, setOf(RepostDestination.TIKTOK), cutoff))
            tiktok.trackPublishForListing(id, attempt, "fresh-draft", "DRAFT", cutoff)
            assertEquals("fresh-draft", tiktok.trackedPublishes(cutoff + 1, window).single().publishId)
        }
    }

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
            // A query touching repostTab must fail, even if it uses a LEFT JOIN.
            BundledSQLiteDriver().open(path.toString()).use { it.execSQL("DROP TABLE repostTab") }
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

    @Test fun `repost lifecycle does not rewrite advertisement and content edits preserve history`() {
        val path = Files.createTempDirectory("repost-isolation").resolve("test.db")
        RoomDatabaseStore(path).use { db ->
            val repo = CatalogRepository(db)
            val id = repo.save(ad())
            val before = repo.listing(id)
            val destination = RepostDestination.TIKTOK
            val first = assertNotNull(repo.prepare(id, setOf(destination), 200))
            repo.recover(300)
            assertEquals(RepostStatus.Pending, repo.status(assertNotNull(repo.repost(id)), destination))
            val second = assertNotNull(repo.prepare(id, setOf(destination), 400))
            assertNotEquals(first, second)
            repo.changeAttempt(id, destination, second, 500) {
                it.copy(status = RepostStatus.AwaitingConfirmation, publishId = "publish-id")
            }
            assertEquals("publish-id", TikTokRepositoryImpl(db, repo).trackedPublishes(500, 1000).single().publishId)
            repo.updatePublish("publish-id", 600) { it.copy(status = RepostStatus.Published) }
            assertEquals(before, repo.listing(id))
            val published = assertNotNull(repo.repost(id))
            assertEquals(600L, published.tiktokRepostedAt)
            repo.save(assertNotNull(before).copy(title = "Edited"))
            assertEquals(published, repo.repost(id))
            assertNull(repo.nextRepost(true, false))
        }
    }
}
