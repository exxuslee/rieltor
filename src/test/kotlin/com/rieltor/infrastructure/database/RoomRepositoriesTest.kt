package com.rieltor.infrastructure.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.rieltor.domain.model.RepostDestination
import com.rieltor.domain.model.SourceMessage
import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.model.ListingEntity
import com.rieltor.infrastructure.database.repository.CatalogRepository
import com.rieltor.infrastructure.database.repository.TikTokPublishThrottleRepositoryImpl
import com.rieltor.web.CatalogQuery
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class RoomPersistenceTest {
    private fun path() = Files.createTempDirectory("catalog-test").resolve("test.db")
    private fun source(id: Long = 1, text: String = "Ірпінь\nКвартира\nЦіна: 82 000 USD") =
        SourceMessage(-100, id, 20, text = text, raw = text, sourceCreatedAt = id * 1000)
    private fun listing(id: Long, price: Long = 8_200_000, city: String = "IRPIN", programs: String = "[]") =
        ListingEntity(groupKey = "test:$id", chatId = -100, messageId = id, messageThreadId = 20,
            rawMessage = "private phone", sourceRevision = "1", title = "Квартира $id", location = city,
            typeOfRealty = "APARTMENT", price = price, currency = "USD", governmentPrograms = programs,
            sourceCreatedAt = id * 1000, receivedAt = 1000, cdt = 1000, updatedAt = 1000, status = "ACTIVE")

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

    @Test fun `edit or album extension invalidates download token and stale promotion`() {
        RoomDatabaseStore(path()).use { db ->
            val repo = CatalogRepository(db)
            val source = source().copy(mediaAlbumId = 99)
            repo.receive(source, 0, 1_200_000)
            val rows = repo.group(source.groupKey)
            assertTrue(repo.stage(rows, "READY_FOR_MEDIA", 1_200_001))
            val token = assertNotNull(repo.claim(rows, 1_200_001))
            repo.receive(source.copy(messageId = 2), 1_200_002, 1_200_000)
            assertFalse(repo.promote(rows, token, listing(1).copy(groupKey = source.groupKey), 1_200_003))
            assertTrue(repo.listings().isEmpty())
            assertEquals(2, repo.group(source.groupKey).size)
        }
    }

    @Test fun `promotion is idempotent and deletion hides catalog`() {
        RoomDatabaseStore(path()).use { db ->
            val repo = CatalogRepository(db); val source = source()
            repo.receive(source, 0, 1_200_000)
            val rows = repo.group(source.groupKey)
            repo.stage(rows, "READY_FOR_MEDIA", 1_200_001)
            val token = assertNotNull(repo.claim(rows, 1_200_001))
            assertTrue(repo.promote(rows, token, listing(1).copy(groupKey = source.groupKey), 1_200_002))
            assertFalse(repo.promote(rows, token, listing(1).copy(groupKey = source.groupKey), 1_200_003))
            assertEquals(1, repo.listings().size)
            repo.delete(-100, 1, 1_200_004)
            assertEquals("HIDDEN", repo.listings().single().status)
        }
    }

    @Test fun `draft is not published and confirmation updates boolean atomically`() {
        val path = path()
        RoomDatabaseStore(path).use { db ->
            val repo = CatalogRepository(db); val id = repo.save(listing(1))
            val attempt = assertNotNull(repo.prepare(id, setOf(RepostDestination.TIKTOK), 1000))
            val throttle = TikTokPublishThrottleRepositoryImpl(db, repo)
            throttle.trackPublishForListing(id, attempt, "pub-1", "DRAFT", 2000)
            throttle.updateTrackedStatus("pub-1", "SEND_TO_USER_INBOX", 3000)
            assertFalse(repo.listing(id)!!.tiktokReposted)
        }
        RoomDatabaseStore(path).use { db ->
            val repo = CatalogRepository(db); val throttle = TikTokPublishThrottleRepositoryImpl(db, repo)
            assertEquals("pub-1", throttle.trackedPublishes(10_000, 86_400_000).single().publishId)
            throttle.updateTrackedStatus("pub-1", "PUBLISH_COMPLETE", 11_000)
            assertTrue(repo.listings().single().tiktokReposted)
            assertTrue(throttle.trackedPublishes(12_000, 86_400_000).isEmpty())
        }
    }

    @Test fun `filters use inclusive prices AND groups OR programs and stable cursor`() {
        RoomDatabaseStore(path()).use { db ->
            val repo = CatalogRepository(db)
            repo.save(listing(1, programs = "[\"EOSELIA\"]")); repo.save(listing(2, programs = "[\"CERTIFICATE\"]"))
            repo.save(listing(3, city = "BUCHA")); repo.save(listing(4, price = 9_000_000))
            val api = CatalogQuery(repo, "http://localhost")
            val params = Parameters.build {
                append("location", "IRPIN"); append("governmentPrograms", "EOSELIA,CERTIFICATE")
                append("currency", "USD"); append("transactionType", "SALE"); append("pricePeriod", "TOTAL")
                append("priceMin", "82000"); append("priceMax", "82000"); append("limit", "1")
            }
            val first = api.list(params); assertEquals("Квартира 2", first.items.single().title)
            val second = api.list(Parameters.build { appendAll(params); append("cursor", assertNotNull(first.nextCursor)) })
            assertEquals("Квартира 1", second.items.single().title); assertNull(second.nextCursor)
            assertFalse(Json.encodeToString(first).contains("private phone"))
            assertFailsWith<IllegalArgumentException> { api.list(Parameters.build { append("priceMin", "1") }) }
            assertFailsWith<IllegalArgumentException> { api.list(Parameters.build { append("location", "INVALID") }) }
        }
    }

    @Test fun `v11 migration leaves two tables preserves raw pending and limiter`() {
        val path = path()
        val schema = Json.parseToJsonElement(Files.readString(Path.of("schemas/com.rieltor.infrastructure.database.local.RieltorDatabase/11.json"))).jsonObject["database"]!!.jsonObject
        BundledSQLiteDriver().open(path.toString()).use { connection ->
            schema["entities"]!!.jsonArray.forEach { entity ->
                val obj = entity.jsonObject; val table = obj["tableName"]!!.jsonPrimitive.content
                connection.execSQL(obj["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                obj["indices"]?.jsonArray.orEmpty().forEach { connection.execSQL(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            connection.execSQL("INSERT INTO received_telegram_messages VALUES (1,-100,20,'82000:USD','address','raw original','[]','PROCESSING',NULL,NULL,100,101)")
            connection.execSQL("INSERT INTO repost_publications VALUES (1,'TIKTOK',20,'82000:USD','address','PROCESSING',NULL,'pub-1',NULL,100,101)")
            connection.execSQL("INSERT INTO tiktok_tracked_publishes VALUES ('pub-1','DRAFT',100000,'SEND_TO_USER_INBOX',101000)")
            connection.execSQL("INSERT INTO tiktok_publish_attempts VALUES (1,100000)")
            connection.execSQL("INSERT INTO tiktok_publish_throttle VALUES (1,9999999)")
            connection.execSQL("PRAGMA user_version=11")
        }
        RoomDatabaseStore(path).use { db ->
            val repo = CatalogRepository(db)
            val old = repo.listings().single()
            assertNull(old.messageId); assertEquals("NEEDS_REVIEW", old.status)
            assertTrue(old.rawMessage.contains("raw original")); assertFalse(old.tiktokReposted)
            assertEquals("DELIVERED_DRAFT", old.tiktokStatus)
            assertEquals(9999999, db.settings.snapshot().blockedUntil)
            assertEquals(100000, db.settings.snapshot().slotReservations.single().reservedAt)
        }
        BundledSQLiteDriver().open(path.toString()).use { connection ->
            connection.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name != 'room_master_table'").use { query ->
                val names = buildSet { while(query.step()) add(query.getText(0)) }
                assertEquals(setOf("incoming_telegram_messages", "listings"), names)
            }
        }
    }
}
