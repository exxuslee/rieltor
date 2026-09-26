package com.rieltor.infrastructure.database

import com.rieltor.application.service.StatisticsService
import com.rieltor.domain.model.ListingStatus
import com.rieltor.domain.model.RepostDestination
import com.rieltor.domain.model.RepostStatus
import com.rieltor.domain.model.SourceMessage
import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.model.AdEntity
import com.rieltor.infrastructure.database.model.IncomingStatus
import com.rieltor.infrastructure.database.model.StatisticsEvent
import com.rieltor.infrastructure.database.repository.CatalogRepository
import com.rieltor.web.routing.systemRoutes
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatisticsTest {
    @Test fun `v31 migration removes visitor data and retains advertisement statistics`() = runBlocking {
        val path = Files.createTempDirectory("remove-visitor-data").resolve("test.db")
        val schema = Json.parseToJsonElement(Files.readString(java.nio.file.Path.of(
            "schemas/com.rieltor.infrastructure.database.local.RieltorDatabase/31.json"
        ))).jsonObject.getValue("database").jsonObject.getValue("entities").jsonArray
        androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(path.toString()).use { connection ->
            fun sql(value: String) { connection.prepare(value).use { it.step() } }
            schema.forEach { entity ->
                val data = entity.jsonObject
                val name = data.getValue("tableName").jsonPrimitive.content
                sql(data.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", name))
                data["indices"]?.jsonArray.orEmpty().forEach {
                    sql(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", name))
                }
            }
            sql("INSERT INTO siteVisitors VALUES ('2026-09-26', 'old-browser-id')")
            sql("INSERT INTO statisticsEvents (kind, sourceKey, chatId, messageId, occurredAt, messageThreadId) VALUES ('RECEIVED', '-1:7', -1, 7, 1000, 77)")
            sql("PRAGMA user_version=31")
        }
        RoomDatabaseStore(path).use { db ->
            val channel = StatisticsService(db, clock).snapshot().channels.single()
            assertEquals(1L, channel.total.received)
            assertEquals("77", channel.topics.single().messageThreadId)
        }
        androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(path.toString()).use { connection ->
            connection.prepare("SELECT name FROM sqlite_master WHERE name='siteVisitors'").use { assertFalse(it.step()) }
        }
    }
    @Test fun `topics sum to channel totals and include named empty and unknown topics`() = runBlocking {
        RoomDatabaseStore(Files.createTempDirectory("topic-statistics").resolve("test.db")).use { db ->
            db.settings.update { it.copy(topicNames = mapOf("-1:77" to "Будинки з ремонтом", "-1:88" to "ДІЛЯНКИ", "-2:99" to "Інший канал")) }
            val dao = db.room.statisticsDao()
            dao.record(StatisticsEvent("RECEIVED", "sunday", -1, 1, occurredAt = Instant.parse("2026-09-20T20:59:59Z").toEpochMilli(), messageThreadId = 77))
            dao.record(StatisticsEvent("RECEIVED", "monday", -1, 2, occurredAt = Instant.parse("2026-09-20T21:00:00Z").toEpochMilli(), messageThreadId = 77))
            dao.record(StatisticsEvent("RECEIVED", "unknown", -1, 3, occurredAt = null))
            dao.record(StatisticsEvent("RECEIVED", "general", -1, 4, occurredAt = now.toEpochMilli(), messageThreadId = 0))
            dao.record(StatisticsEvent("TIKTOK", "publication", -1, 2, occurredAt = now.toEpochMilli(), messageThreadId = 77))
            val repo = CatalogRepository(db)
            repo.save(AdEntity(chatId = -1, messageId = 2, messageThreadId = 77, adId = "active-topic", sourceRevision = "r",
                timestamp = now.toEpochMilli(), status = ListingStatus.Active, price = 100, currency = "USD"))
            val snapshot = StatisticsService(db, clock).snapshot("week")
            val channel = snapshot.channels.single { it.chatId == "-1" }
            assertEquals(4L, channel.total.received)
            assertEquals(2L, channel.week.received)
            assertEquals(1L, channel.day.received)
            assertEquals(channel.total.received, channel.topics.sumOf { it.total.received })
            assertEquals(channel.active, channel.topics.sumOf { it.active })
            assertEquals(1L, channel.topics.single { it.messageThreadId == "77" }.active)
            assertEquals("Будинки з ремонтом", channel.topics.single { it.messageThreadId == "77" }.name)
            assertEquals(0L, channel.topics.single { it.messageThreadId == "88" }.total.received)
            assertEquals("Підгрупа невідома", channel.topics.single { it.messageThreadId == null }.name)
            assertEquals("Без підгрупи", channel.topics.single { it.messageThreadId == "0" }.name)
            assertEquals("Інший канал", snapshot.channels.single { it.chatId == "-2" }.topics.single().name)
            assertEquals("77", snapshot.publications.single().messageThreadId)
            assertEquals("Будинки з ремонтом", snapshot.publications.single().topicName)
        }
    }
    private val now = Instant.parse("2026-09-26T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test fun `receive edit promote publish cleanup and restart preserve unique totals`() = runBlocking {
        val path = Files.createTempDirectory("statistics").resolve("test.db")
        RoomDatabaseStore(path).use { db ->
            val repo = CatalogRepository(db)
            val source = SourceMessage(-100, 7, 0, "text", "{}", now.toEpochMilli())
            repo.receive(source, now.toEpochMilli(), 0)
            repo.receive(source, now.toEpochMilli(), 0)
            repo.receive(source.copy(text = "edited", sourceEditedAt = now.toEpochMilli()), now.toEpochMilli(), 0)
            val row = requireNotNull(repo.source(-100, 7))
            assertTrue(repo.stage(row, IncomingStatus.ReadyForMedia, now.toEpochMilli()))
            assertTrue(repo.claim(row, now.toEpochMilli()))
            assertTrue(repo.promote(row, AdEntity(chatId = -100, messageId = 7, messageThreadId = 0,
                adId = "unique-ad", sourceRevision = "r", timestamp = now.toEpochMilli(),
                status = ListingStatus.Active, price = 50000, currency = "USD"), now.toEpochMilli()))
            val id = repo.listings().single().id
            val attempt = requireNotNull(repo.prepare(id, setOf(RepostDestination.TIKTOK, RepostDestination.THREADS), now.toEpochMilli()))
            repo.changeAttempt(id, RepostDestination.TIKTOK, attempt, now.toEpochMilli()) { it.copy(status = RepostStatus.AwaitingConfirmation) }
            assertEquals(0, StatisticsService(db, clock).snapshot().channels.single().day.tiktok)
            repo.changeAttempt(id, RepostDestination.THREADS, attempt, now.toEpochMilli()) { it.copy(status = RepostStatus.Published) }
            repeat(2) { repo.changeAttempt(id, RepostDestination.TIKTOK, attempt, now.toEpochMilli()) { it.copy(status = RepostStatus.Published) } }
            val snapshot = StatisticsService(db, clock).snapshot()
            assertEquals(listOf(1L, 1L, 1L, 1L, 1L), snapshot.channels.single().day.let { listOf(it.received, it.processed, it.accepted, it.tiktok, it.threads) })
            assertEquals(1, snapshot.channels.single().active)
            assertEquals(setOf("TIKTOK", "THREADS"), snapshot.publications.map { it.platform }.toSet())
            assertTrue(snapshot.publications.all { it.listingId == id.toString() && it.adId == "unique-ad" })
            repo.cleanExpired(now.toEpochMilli() + 1)
        }
        RoomDatabaseStore(path).use { db ->
            val snapshot = StatisticsService(db, clock).snapshot()
            assertEquals(1, snapshot.channels.single().total.received)
            assertEquals(0, snapshot.channels.single().active)
            assertEquals(2, snapshot.publications.size)
        }
    }

    @Test fun `calendar periods use Kyiv midnight and unknown dates only count in totals`() = runBlocking {
        RoomDatabaseStore(Files.createTempDirectory("periods").resolve("test.db")).use { db ->
            val dao = db.room.statisticsDao()
            dao.record(StatisticsEvent("RECEIVED", "before", -1, 1, occurredAt = Instant.parse("2026-09-25T20:59:59Z").toEpochMilli()))
            dao.record(StatisticsEvent("RECEIVED", "today", -1, 2, occurredAt = Instant.parse("2026-09-25T21:00:00Z").toEpochMilli()))
            dao.record(StatisticsEvent("RECEIVED", "last-week", -1, 3, occurredAt = Instant.parse("2026-08-31T20:59:59Z").toEpochMilli()))
            dao.record(StatisticsEvent("RECEIVED", "legacy", -1, 4, occurredAt = null))
            val channel = StatisticsService(db, clock).snapshot().channels.single()
            assertEquals(1, channel.day.received); assertEquals(2, channel.week.received); assertEquals(4, channel.total.received)
        }
    }

    @Test fun `health JSON includes zero values and rejects invalid request parameters`() {
        RoomDatabaseStore(Files.createTempDirectory("health-route").resolve("test.db")).use { db ->
            testApplication {
                application { routing { systemRoutes(StatisticsService(db, clock)) } }
                val response = client.get("/health")
                assertEquals(HttpStatusCode.OK, response.status)
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals("ok", body.getValue("status").jsonPrimitive.content)
                assertEquals("Europe/Kyiv", body.getValue("timezone").jsonPrimitive.content)
                assertFalse("visitors" in body)
                assertEquals(HttpStatusCode.BadRequest, client.get("/health?period=invalid").status)
                assertEquals(HttpStatusCode.BadRequest, client.get("/health?offset=bad").status)
                assertEquals(HttpStatusCode.NotFound, client.post("/api/visits/7b15cfa2-35ed-4d69-983f-fce07d22c5e0").status)
            }
        }
    }
}
