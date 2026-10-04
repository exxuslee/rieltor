package com.rieltor.infrastructure.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.rieltor.domain.model.*
import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.model.AdEntity
import com.rieltor.infrastructure.database.model.RepostEntity
import com.rieltor.infrastructure.database.repository.CatalogRepository
import com.rieltor.infrastructure.database.repository.TikTokRepositoryImpl
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.*

class RepostHistoryMigrationTest {
    private fun listing(id: Long) = AdEntity(
        id = id, adId = "ad-$id", chatId = REPOST_SOURCE_CHAT_ID, messageId = id,
        messageThreadId = 77, sourceRevision = "1", title = "Listing $id", timestamp = id,
        status = ListingStatus.Active, price = 80000, currency = "USD",
    )

    private fun state(status: RepostStatus, time: Long, publishId: String? = null) =
        Json.encodeToString(PublicationState(listOf(
            PublishAttempt("attempt-$time", status, publishId, "DRAFT", time - 1, time),
        )))

    @Test fun `v33 migration restores delivered dates removes unsent rows and preserves unresolved attempts`() {
        val path = Files.createTempDirectory("repost-history-migration").resolve("test.db")
        // All tables besides repostAttemptsTab are identical to the v33 schema.
        RoomDatabaseStore(path).use { db ->
            val repo = CatalogRepository(db)
            for (id in 1L..7L) repo.save(listing(id))
            val rows = listOf(
                RepostEntity(1, tiktokStatus = "DELIVERED_DRAFT", tiktokState = state(RepostStatus.DeliveredDraft, 200, "draft")),
                RepostEntity(2),
                RepostEntity(3, tiktokStatus = "UNKNOWN", tiktokState = state(RepostStatus.Unknown, 300)),
                RepostEntity(4, tiktokStatus = "AWAITING_CONFIRMATION", tiktokState = state(RepostStatus.AwaitingConfirmation, 400, "processing")),
                RepostEntity(5, tiktokStatus = "FAILED", tiktokState = state(RepostStatus.Failed, 500)),
                RepostEntity(6, threadsRepostedAt = 600, threadsStatus = "PUBLISHED"),
                RepostEntity(7, tiktokStatus = "PUBLISHED", tiktokState = state(RepostStatus.Published, 700, "post")),
            )
            db.blocking { room -> rows.forEach { room.catalogDao().persistRepost(it) } }
        }
        BundledSQLiteDriver().open(path.toString()).use {
            it.execSQL("DROP TABLE repostAttemptsTab")
            it.execSQL("PRAGMA user_version=33")
        }
        repeat(2) {
            RoomDatabaseStore(path).use { db ->
                val repo = CatalogRepository(db)
                assertEquals(7, repo.listings().size)
                assertEquals(setOf(1L, 4L, 6L, 7L), repo.reposts().map { it.id }.toSet())
                assertEquals(200L, repo.repost(1)?.tiktokRepostedAt)
                assertEquals(600L, repo.repost(6)?.threadsRepostedAt)
                assertEquals(700L, repo.repost(7)?.tiktokRepostedAt)
                assertNull(repo.repost(4)?.tiktokRepostedAt)
                assertNull(repo.repostState(2))
                assertEquals(RepostStatus.Unknown, repo.status(assertNotNull(repo.repostState(3)), RepostDestination.TIKTOK))
                assertFailsWith<IllegalStateException> { repo.prepareManual(3, RepostDestination.TIKTOK, 800) }
                assertNotNull(repo.nextRepost(true, false))
                val publications = db.blocking { room -> room.statisticsDao().publications(null, 50, 0) }
                assertEquals(setOf(200L, 600L, 700L), publications.map { it.occurredAt }.toSet())
                assertTrue(publications.all { it.messageThreadId == 77L })
            }
        }
    }

    @Test fun `only acknowledged sends enter history and draft delivery counts once across restarts`() {
        val path = Files.createTempDirectory("repost-sent-history").resolve("test.db")
        RoomDatabaseStore(path).use { db ->
            val repo = CatalogRepository(db)
            repo.save(listing(1))
            val attempt = assertNotNull(repo.prepare(1, setOf(RepostDestination.TIKTOK), 100))
            assertTrue(repo.reposts().isEmpty())
            repo.changeAttempt(1, RepostDestination.TIKTOK, attempt, 110) { it.copy(status = RepostStatus.Sending) }
            assertTrue(repo.reposts().isEmpty())
            val tiktok = TikTokRepositoryImpl(db, repo)
            tiktok.trackPublishForListing(1, attempt, "draft", "DRAFT", 120)
            assertNotNull(repo.repost(1))
            assertNull(repo.repost(1)?.tiktokRepostedAt)
            tiktok.updateTrackedStatus("draft", "SEND_TO_USER_INBOX", 130)
            assertEquals(130L, repo.repost(1)?.tiktokRepostedAt)
        }
        RoomDatabaseStore(path).use { db ->
            val repo = CatalogRepository(db)
            val tiktok = TikTokRepositoryImpl(db, repo)
            tiktok.updateTrackedStatus("draft", "SEND_TO_USER_INBOX", 140)
            tiktok.updateTrackedStatus("draft", "PUBLISH_COMPLETE", 150)
            assertEquals(130L, repo.repost(1)?.tiktokRepostedAt)
            assertEquals(RepostStatus.Published, repo.status(assertNotNull(repo.repost(1)), RepostDestination.TIKTOK))
            assertEquals(1L, db.blocking { it.statisticsDao().counts(null) }.single { it.kind == "TIKTOK" }.count)
            assertEquals(130L, db.blocking { it.statisticsDao().publications(null, 50, 0) }.single().occurredAt)
            assertNull(repo.nextRepost(true, false))
            db.blocking { it.catalogDao().deleteListing(1) }
            assertTrue(repo.reposts().isEmpty())
            assertTrue(repo.repostStates().isEmpty())
        }
    }
}
