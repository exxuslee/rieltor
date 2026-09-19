package com.rieltor.infrastructure.database

import com.rieltor.domain.model.RepostDestination
import com.rieltor.domain.model.SourceMessage
import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.model.ListingEntity
import com.rieltor.infrastructure.database.repository.CatalogRepository
import com.rieltor.infrastructure.database.repository.TikTokRepositoryImpl
import com.rieltor.web.CatalogQuery
import io.ktor.http.*
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.*

class RoomPersistenceTest {
    private fun path() = Files.createTempDirectory("catalog-test").resolve("test.db")
    private fun source(id: Long = 1, text: String = "Ірпінь\nКвартира\nЦіна: 82 000 USD") =
        SourceMessage(-100, id, 20, text = text, raw = text, sourceCreatedAt = id * 1000)
    private fun listing(id: Long, price: Long = 82_000, city: String = "IRPIN", programs: String = "[]",
        type: String = "APARTMENT 1", rawText: String = "") =
        ListingEntity(adId = "test-ad:$id", chatId = -100, messageId = id, messageThreadId = 20,
            sourceRevision = "1", title = "Квартира $id", location = city,
            typeOfRealty = type, rawText = rawText, price = price, currency = "USD", governmentPrograms = programs,
            sourceCreatedAt = id * 1000, createdAt = 1000, updatedAt = 1000, status = "ACTIVE")

    @Test fun `inbox survives restart and edits reset deadline without dropping old messages`() {
        val path = path()
        RoomDatabaseStore(path).use { db ->
            val repo = CatalogRepository(db)
            repeat(70) { repo.receive(source(it + 1L), 1000, 1_200_000) }
            repo.receive(source(), 5000, 1_200_000)
            assertEquals(1_201_000, repo.source(-100, 1)?.verifyAfter)
            repo.receive(source(text = "Нова версія"), 1_000_000, 1_200_000)
        }
        RoomDatabaseStore(path).use { db ->
            val repo = CatalogRepository(db)
            assertEquals(70, repo.groups().size)
            assertEquals(2_200_000, repo.source(-100, 1)?.verifyAfter)
            assertEquals(2, repo.source(-100, 1)?.revision)
        }
    }

    @Test fun `edit invalidates download token and stale promotion`() {
        RoomDatabaseStore(path()).use { db ->
            val repo = CatalogRepository(db)
            val source = source()
            repo.receive(source, 0, 1_200_000)
            val rows = repo.group(source.chatId, source.messageId)
            assertTrue(repo.stage(rows, "READY_FOR_MEDIA", 1_200_001))
            val token = assertNotNull(repo.claim(rows, 1_200_001))
            repo.receive(source.copy(text = "Оновлена версія", raw = "Оновлена версія", sourceEditedAt = 1), 1_200_002, 1_200_000)
            assertFalse(repo.promote(rows, token, listing(1), 1_200_003))
            assertTrue(repo.listings().isEmpty())
            assertEquals(1, repo.group(source.chatId, source.messageId).size)
        }
    }

    @Test fun `promotion is idempotent and deletion hides catalog`() {
        RoomDatabaseStore(path()).use { db ->
            val repo = CatalogRepository(db); val source = source()
            repo.receive(source, 0, 1_200_000)
            val rows = repo.group(source.chatId, source.messageId)
            repo.stage(rows, "READY_FOR_MEDIA", 1_200_001)
            val token = assertNotNull(repo.claim(rows, 1_200_001))
            assertTrue(repo.promote(rows, token, listing(1), 1_200_002))
            assertFalse(repo.promote(rows, token, listing(1), 1_200_003))
            assertEquals(1, repo.listings().size)
            repo.delete(-100, 1, 1_200_004)
            assertEquals("HIDDEN", repo.listings().single().status)
        }
    }

    @Test fun `draft is not published and confirmation records publication time atomically`() {
        val path = path()
        RoomDatabaseStore(path).use { db ->
            val repo = CatalogRepository(db); val id = repo.save(listing(1))
            val attempt = assertNotNull(repo.prepare(id, setOf(RepostDestination.TIKTOK), 1000))
            val throttle = TikTokRepositoryImpl(db, repo)
            throttle.trackPublishForListing(id, attempt, "pub-1", "DRAFT", 2000)
            throttle.updateTrackedStatus("pub-1", "SEND_TO_USER_INBOX", 3000)
            assertNull(repo.listing(id)!!.tiktokRepostedAt)
        }
        RoomDatabaseStore(path).use { db ->
            val repo = CatalogRepository(db); val throttle = TikTokRepositoryImpl(db, repo)
            assertEquals("pub-1", throttle.trackedPublishes(10_000, 86_400_000).single().publishId)
            throttle.updateTrackedStatus("pub-1", "PUBLISH_COMPLETE", 11_000)
            assertEquals(11_000, repo.listings().single().tiktokRepostedAt)
            assertTrue(throttle.trackedPublishes(12_000, 86_400_000).isEmpty())
        }
    }

    @Test fun `filters use inclusive prices AND groups OR programs and stable cursor`() {
        RoomDatabaseStore(path()).use { db ->
            val repo = CatalogRepository(db)
            repo.save(listing(1, programs = "[\"EOSELIA\"]")); repo.save(listing(2, programs = "[\"CERTIFICATE\"]"))
            repo.save(listing(3, city = "BUCHA")); repo.save(listing(4, price = 90_000))
            val api = CatalogQuery(repo, "http://localhost")
            val params = Parameters.build {
                append("location", "IRPIN"); append("governmentPrograms", "EOSELIA,CERTIFICATE")
                append("priceMin", "82000"); append("priceMax", "82000"); append("limit", "1")
            }
            val first = api.list(params); assertEquals("Квартира 2", first.items.single().title)
            val second = api.list(Parameters.build { appendAll(params); append("cursor", assertNotNull(first.nextCursor)) })
            assertEquals("Квартира 1", second.items.single().title); assertNull(second.nextCursor)
            assertFalse(Json.encodeToString(first).contains("private phone"))
            assertEquals(4, api.list(Parameters.build { append("priceMin", "1") }).items.size)
            assertFailsWith<IllegalArgumentException> { api.list(Parameters.build { append("location", "INVALID") }) }
        }
    }

    @Test fun `accepts only normalized active prices and preserves them on restart`() {
        val path = path()
        RoomDatabaseStore(path).use { db ->
            val repo = CatalogRepository(db)
            assertFailsWith<IllegalArgumentException> { repo.save(listing(1).copy(currency = "UAH")) }
            assertFailsWith<IllegalArgumentException> { repo.save(listing(2, price = 0)) }
            repo.save(listing(3, price = 50_500))
            val hidden = repo.save(listing(4).copy(status = "NEEDS_REVIEW", price = null))
            val api = CatalogQuery(repo, "http://localhost")
            assertEquals(listOf("50500"), api.list(Parameters.Empty).items.map { it.price })
            assertNull(api.one(hidden))
        }
        RoomDatabaseStore(path).use { db ->
            db.settings.update { it.copy(uahPerUsd = 50.0) }
            assertEquals(50_500, CatalogRepository(db).listings().first { it.messageId == 3L }.price)
        }
    }

    @Test fun `catalog exposes raw source text from listing`() {
        RoomDatabaseStore(path()).use { db ->
            val repo = CatalogRepository(db)
            repo.save(listing(1, rawText = "Ірпінь\nЦіна: 82 000 USD"))

            assertEquals("Ірпінь\nЦіна: 82 000 USD", CatalogQuery(repo, "http://localhost").list(Parameters.Empty).items.single().rawText)
        }
    }

    @Test fun `filters detailed property types and exposes their frontend categories`() {
        RoomDatabaseStore(path()).use { db ->
            val repo = CatalogRepository(db)
            repo.save(listing(1, type = "APARTMENT 1+"))
            repo.save(listing(2, type = "APARTMENT 2"))
            repo.save(listing(3, type = "HOUSE-"))
            repo.save(listing(4, type = "DUPLEX+"))
            repo.save(listing(5, type = "LAND"))
            val api = CatalogQuery(repo, "http://localhost")

            val apartments = api.list(Parameters.build {
                append("typeOfRealty", "APARTMENT 1+,APARTMENT 2")
            }).items
            assertEquals(setOf("APARTMENT 1+", "APARTMENT 2"), apartments.map { it.typeOfRealty }.toSet())
            assertTrue(apartments.all { it.category == "apartments" })
            assertEquals("houses", api.list(Parameters.build { append("typeOfRealty", "HOUSE-") }).items.single().category)
            assertEquals("duplexes", api.list(Parameters.build { append("typeOfRealty", "DUPLEX+") }).items.single().category)
            assertEquals("land", api.list(Parameters.build { append("typeOfRealty", "LAND") }).items.single().category)
        }
    }

    @Test fun `duplicate uses latest Telegram date for display and paginated sorting`() {
        RoomDatabaseStore(path()).use { db ->
            val repo = CatalogRepository(db)
            fun promote(id: Long): Boolean {
                val message = source(id)
                repo.receive(message, 100_000, 0)
                val rows = repo.group(message.chatId, message.messageId)
                repo.stage(rows, "READY_FOR_MEDIA", 100_000)
                val token = assertNotNull(repo.claim(rows, 100_000))
                return repo.promote(rows, token, listing(id).copy(
                    adId = "same-ad",
                    googleDriveUrls = "[\"https://drive.google.com/drive/folders/same-folder\"]"
                ), 100_001)
            }
            assertTrue(promote(1))
            val originalId = repo.listings().single().id
            repo.save(listing(2).copy(createdAt = 999_999))
            assertTrue(promote(3))
            assertFalse(promote(1))
            assertEquals(2, repo.listings().size)
            val api = CatalogQuery(repo, "http://localhost")
            val params = Parameters.build { append("sort", "newest"); append("limit", "1") }
            val first = api.list(params)
            assertEquals(originalId.toString(), first.items.single().id)
            assertEquals(3000L, first.items.single().sourceCreatedAt)
            assertEquals(1000L, first.items.single().cdt)
            assertEquals(3000L, api.one(originalId)?.sourceCreatedAt)
            val second = api.list(Parameters.build {
                appendAll(params); append("cursor", assertNotNull(first.nextCursor))
            })
            assertEquals(2000L, second.items.single().sourceCreatedAt)
            assertNull(second.nextCursor)
        }
    }

    @Test fun `SQL repost selection respects destinations statuses and deterministic order`() {
        RoomDatabaseStore(path()).use { db ->
            val repo = CatalogRepository(db)
            repo.save(listing(1).copy(threadsStatus = "PUBLISHED"))
            val second = repo.save(listing(2).copy(tiktokStatus = "FAILED"))
            repo.save(listing(3).copy(status = "HIDDEN"))
            assertEquals(1L, repo.nextRepost(true, false)?.messageId)
            assertEquals(second, repo.nextRepost(false, true)?.id)
            assertEquals(second, repo.nextRepost(true, true)?.id)
            assertNull(repo.nextRepost(false, false))
        }
    }

}
