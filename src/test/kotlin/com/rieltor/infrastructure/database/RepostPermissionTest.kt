package com.rieltor.infrastructure.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.rieltor.domain.model.ListingStatus
import com.rieltor.domain.model.REPOST_SOURCE_CHAT_ID
import com.rieltor.domain.model.RepostDestination
import com.rieltor.domain.model.SourceMessage
import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.model.AdEntity
import com.rieltor.infrastructure.database.repository.CatalogRepository
import java.nio.file.Files
import kotlin.test.*

class RepostPermissionTest {
    private fun source(text: String, edited: Long = 0) = SourceMessage(
        REPOST_SOURCE_CHAT_ID, 1, 0, text, "", 100, sourceEditedAt = edited,
    )

    private fun listing(text: String = "", messageId: Long = 1) = AdEntity(
        chatId = REPOST_SOURCE_CHAT_ID, messageId = messageId, messageThreadId = 0,
        adId = "ad-$messageId", sourceRevision = "1", rawText = text,
        status = ListingStatus.Active, price = 10000, currency = "USD", timestamp = messageId,
    )

    @Test fun `permission is persisted and recalculated after caption edits`() {
        RoomDatabaseStore(Files.createTempDirectory("repost-permission").resolve("test.db")).use { db ->
            val repo = CatalogRepository(db)
            repo.receive(source("Продам квартиру"), 100, 0)
            assertTrue(assertNotNull(repo.source(REPOST_SOURCE_CHAT_ID, 1)).isAllowRepost)
            repo.receive(source("Продам квартиру, БеЗ РеКлАмИ!", 200), 200, 0)
            assertFalse(assertNotNull(repo.source(REPOST_SOURCE_CHAT_ID, 1)).isAllowRepost)
            val blocked = repo.save(listing())
            val allowed = repo.save(listing(messageId = 2))
            assertEquals(allowed, repo.nextRepost(true, true)?.listing?.id)
            assertNull(repo.prepare(blocked, RepostDestination.entries.toSet(), 300))
            assertFailsWith<IllegalStateException> { repo.prepareManual(blocked, RepostDestination.TIKTOK, 300) }
            assertNull(repo.repostState(blocked))
            repo.receive(source("Продам квартиру", 400), 400, 0)
            assertTrue(assertNotNull(repo.source(REPOST_SOURCE_CHAT_ID, 1)).isAllowRepost)
            repo.save(assertNotNull(repo.listing(blocked)).copy(status = ListingStatus.Active))
            assertNotNull(repo.prepareManual(blocked, RepostDestination.THREADS, 500))
        }
    }

    @Test fun `repost permission uses incoming column rather than catalog text`() {
        RoomDatabaseStore(Files.createTempDirectory("repost-without-source").resolve("test.db")).use { db ->
            val repo = CatalogRepository(db)
            val allowed = repo.save(listing(messageId = 1))
            repo.receive(source("Квартира").copy(messageId = 2), 100, 0)
            val allowedWithOldText = repo.save(listing("Квартира — БЕЗ РЕКЛАМИ", 2))
            val incoming = assertNotNull(repo.source(REPOST_SOURCE_CHAT_ID, 2))
            db.blocking { it.catalogDao().saveSource(incoming.copy(isAllowRepost = false)) }
            assertEquals(allowed, repo.nextRepost(true, false)?.listing?.id)
            assertFalse(repo.isAllowRepost(allowedWithOldText))
            assertNull(repo.prepare(allowedWithOldText, setOf(RepostDestination.TIKTOK), 300))
            assertFailsWith<IllegalStateException> { repo.prepareManual(allowedWithOldText, RepostDestination.TIKTOK, 300) }
            db.blocking { it.catalogDao().saveSource(incoming.copy(isAllowRepost = true)) }
            assertEquals(allowedWithOldText, repo.nextRepost(true, false)?.listing?.id)
            assertTrue(repo.isAllowRepost(allowedWithOldText))
            assertNotNull(repo.prepareManual(allowedWithOldText, RepostDestination.TIKTOK, 400))
        }
    }

    @Test fun `v34 migration backfills permissions without losing existing messages`() {
        val path = Files.createTempDirectory("repost-permission-migration").resolve("test.db")
        RoomDatabaseStore(path).use { db ->
            val repo = CatalogRepository(db)
            repo.receive(source("Квартира — БЕЗ РЕКЛАМИ"), 100, 0)
            repo.receive(source("Квартира").copy(messageId = 2), 100, 0)
        }
        BundledSQLiteDriver().open(path.toString()).use {
            it.execSQL("ALTER TABLE incomeTab DROP COLUMN isAllowRepost")
            it.execSQL("PRAGMA user_version=34")
        }
        repeat(2) {
            RoomDatabaseStore(path).use { db ->
                val repo = CatalogRepository(db)
                assertEquals(2, repo.incoming().size)
                assertFalse(assertNotNull(repo.source(REPOST_SOURCE_CHAT_ID, 1)).isAllowRepost)
                assertTrue(assertNotNull(repo.source(REPOST_SOURCE_CHAT_ID, 2)).isAllowRepost)
            }
        }
    }
}
