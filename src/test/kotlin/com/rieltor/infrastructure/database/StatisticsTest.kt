package com.rieltor.infrastructure.database

import com.rieltor.application.service.StatisticsService
import com.rieltor.domain.model.ListingStatus
import com.rieltor.domain.model.RepostDestination
import com.rieltor.domain.model.RepostStatus
import com.rieltor.domain.model.SourceMessage
import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.model.AdEntity
import com.rieltor.infrastructure.database.model.IncomingStatus
import com.rieltor.infrastructure.database.model.SiteVisitor
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatisticsTest {
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
            dao.record(StatisticsEvent("RECEIVED", "last-month", -1, 3, occurredAt = Instant.parse("2026-08-31T20:59:59Z").toEpochMilli()))
            dao.record(StatisticsEvent("RECEIVED", "legacy", -1, 4, occurredAt = null))
            val channel = StatisticsService(db, clock).snapshot().channels.single()
            assertEquals(1, channel.day.received); assertEquals(2, channel.month.received); assertEquals(4, channel.total.received)
        }
    }

    @Test fun `visitors deduplicate across pages and days and survive reopening`() = runBlocking {
        val path = Files.createTempDirectory("visitors").resolve("test.db")
        RoomDatabaseStore(path).use { db ->
            val service = StatisticsService(db, clock)
            repeat(3) { service.visit("browser-one") }
            db.room.statisticsDao().visit(SiteVisitor("2026-09-25", "browser-one"))
            db.room.statisticsDao().visit(SiteVisitor("2026-09-25", "browser-two"))
            db.room.statisticsDao().visit(SiteVisitor("2026-08-01", "browser-three"))
            val visitors = service.snapshot().visitors
            assertEquals(1, visitors.day); assertEquals(2, visitors.month); assertEquals(3, visitors.total)
        }
        RoomDatabaseStore(path).use { assertEquals(3, StatisticsService(it, clock).snapshot().visitors.total) }
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
                assertEquals(0L, body.getValue("visitors").jsonObject.getValue("total").jsonPrimitive.long)
                assertEquals(HttpStatusCode.BadRequest, client.get("/health?period=invalid").status)
                assertEquals(HttpStatusCode.BadRequest, client.get("/health?offset=bad").status)
                assertEquals(HttpStatusCode.BadRequest, client.post("/api/visits/invalid").status)
                repeat(2) { assertEquals(HttpStatusCode.NoContent, client.post("/api/visits/7b15cfa2-35ed-4d69-983f-fce07d22c5e0").status) }
                assertEquals(1L, Json.parseToJsonElement(client.get("/health").bodyAsText()).jsonObject.getValue("visitors").jsonObject.getValue("total").jsonPrimitive.long)
            }
        }
    }
}
