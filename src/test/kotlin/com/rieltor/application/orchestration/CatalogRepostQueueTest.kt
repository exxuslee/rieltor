package com.rieltor.application.orchestration

import com.rieltor.domain.model.ListingStatus
import com.rieltor.domain.model.RepostDestination
import com.rieltor.domain.model.RepostStatus
import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.model.AdEntity
import com.rieltor.infrastructure.database.repository.CatalogRepository
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The persisted catalog queue replaces the removed in-memory Telegram coordinator. */
class CatalogRepostQueueTest {
    @Test fun `queue selects newest active listing and survives reopening`() {
        val path = Files.createTempDirectory("repost-queue").resolve("test.db")
        RoomDatabaseStore(path).use { db ->
            val repo = CatalogRepository(db)
            for (n in 1L..3L) repo.save(listing(n))
            repo.save(listing(4).copy(status = ListingStatus.Hidden))
            assertEquals(3L, repo.nextRepost(true, false)?.messageId)
        }
        RoomDatabaseStore(path).use { db ->
            val repo = CatalogRepository(db)
            val newest = assertNotNull(repo.nextRepost(true, false))
            kotlin.test.assertTrue(repo.beginRepost(newest.id, RepostDestination.TIKTOK))
            repo.finishRepost(newest.id, RepostDestination.TIKTOK, RepostStatus.Published, 1001)
            assertEquals(2L, repo.nextRepost(true, false)?.messageId)
            assertEquals(3L, repo.nextRepost(false, true)?.messageId)
        }
    }

    @Test fun `restart never retries a claimed send`() {
        val path = Files.createTempDirectory("repost-recovery").resolve("test.db")
        RoomDatabaseStore(path).use { db ->
            val repo = CatalogRepository(db)
            val id = repo.save(listing(1))
            kotlin.test.assertTrue(repo.beginRepost(id, RepostDestination.TIKTOK))
        }
        RoomDatabaseStore(path).use { db ->
            val repo = CatalogRepository(db)
            repo.recover(2000)
            val id = repo.listings().single().id
            assertEquals("SENDING", repo.listing(id)?.repostTikTok)
            assertEquals("PENDING", repo.listing(id)?.repostThreads)
            assertNull(repo.nextRepost(true, false))
            assertNotNull(repo.nextRepost(false, true))
        }
    }

    private fun listing(n: Long) = AdEntity(adId = "ad$n", chatId = -1002681732909L, messageId = n,
        messageThreadId = 1, sourceRevision = "1", timestamp = n, status = ListingStatus.Active,
        title = "Listing $n", price = 80000, currency = "USD")
}
