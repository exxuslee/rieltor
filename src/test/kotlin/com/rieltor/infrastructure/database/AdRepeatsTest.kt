package com.rieltor.infrastructure.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.rieltor.domain.model.RepostDestination
import com.rieltor.domain.model.RepostStatus
import com.rieltor.domain.model.SourceMessage
import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.mapper.CatalogListingMapper
import com.rieltor.infrastructure.database.model.IncomingEntity
import com.rieltor.infrastructure.database.model.IncomingStatus
import com.rieltor.infrastructure.database.repository.CatalogRepository
import java.nio.file.Files
import kotlin.test.*

class AdRepeatsTest {
    private val minute = 60_000L
    private val week = 7 * 24 * 60 * minute
    private val start = 1_000_000L
    private val processingTime = start + 10 * week

    private fun message(id: Long, created: Long = start, chat: Long = -100, price: Long = 80000) =
        SourceMessage(chat, id, 20,
            "Квартира\nІрпінь\nПлоща: 40 м²\nЦіна: $price USD",
            "", created, userId = 123)

    private fun receive(repo: CatalogRepository, message: SourceMessage): IncomingEntity {
        repo.receive(message, processingTime, 20 * minute)
        return assertNotNull(repo.source(message.chatId, message.messageId))
    }

    private fun promote(repo: CatalogRepository, row: IncomingEntity): Boolean {
        assertTrue(repo.stage(row, IncomingStatus.ReadyForMedia, processingTime + 20 * minute))
        assertTrue(repo.claim(row, processingTime + 20 * minute))
        return repo.promote(row, CatalogListingMapper().fromIncoming(row, "APARTMENT1", processingTime),
            processingTime + 20 * minute)
    }

    @Test fun `new ads start at zero and weekly accepted publications increment once across restarts`() {
        val path = Files.createTempDirectory("ad-repeats").resolve("test.db")
        var listingId = 0L
        RoomDatabaseStore(path).use { db ->
            val repo = CatalogRepository(db)
            assertTrue(promote(repo, receive(repo, message(1))))
            listingId = repo.listings().single().id
            assertEquals(0, repo.listings().single().repeats)

            val next = receive(repo, message(2, start + week, chat = -200))
            assertEquals(0, repo.listings().single().repeats) // Merely receiving is not acceptance.
            assertTrue(promote(repo, next))
            assertEquals(listingId, repo.listings().single().id)
            assertEquals(1, repo.listings().single().repeats)
            val prepared = CatalogListingMapper().fromIncoming(next, "APARTMENT1", processingTime)
            assertFalse(repo.promote(next, prepared, processingTime + week))
            assertEquals(1, repo.listings().single().repeats)
        }
        RoomDatabaseStore(path).use { db ->
            val repo = CatalogRepository(db)
            assertEquals(1, repo.listing(listingId)?.repeats)
            assertTrue(promote(repo, receive(repo, message(3, start + 2 * week))))
            assertEquals(2, repo.listing(listingId)?.repeats)
            val newKey = receive(repo, message(4, start + 3 * week, price = 79000))
            assertTrue(promote(repo, newKey))
            assertEquals(0, repo.listings().single { it.messageId == 4L }.repeats)
            assertEquals(2, repo.listing(listingId)?.repeats)
        }
    }

    @Test fun `edits before and after acceptance never count as repetitions`() {
        RoomDatabaseStore(Files.createTempDirectory("ad-repeat-edits").resolve("test.db")).use { db ->
            val repo = CatalogRepository(db)
            val original = message(1)
            val stale = receive(repo, original)
            val edited = original.copy(text = original.text + "\nБалкон", sourceEditedAt = start + 5 * minute)
            val current = receive(repo, edited)
            assertFalse(repo.stage(stale, IncomingStatus.ReadyForMedia, processingTime))
            assertTrue(promote(repo, current))
            assertEquals(0, repo.listings().single().repeats)

            assertTrue(promote(repo, receive(repo, message(2, start + week))))
            assertEquals(1, repo.listings().single().repeats)
            val latest = message(2, start + week)
            assertTrue(promote(repo, receive(repo,
                latest.copy(text = latest.text + "\nБалкон", sourceEditedAt = start + 2 * week))))
            assertEquals(1, repo.listings().single().repeats)
        }
    }

    @Test fun `separate quick duplicates do not count even when accepted in different batches`() {
        RoomDatabaseStore(Files.createTempDirectory("ad-repeat-burst").resolve("test.db")).use { db ->
            val repo = CatalogRepository(db)
            val original = receive(repo, message(1))
            val duplicate = receive(repo, message(2, start + 5 * minute, chat = -200))
            assertTrue(promote(repo, original))
            assertTrue(promote(repo, duplicate))
            assertEquals(0, repo.listings().single().repeats)
            assertTrue(promote(repo, receive(repo, message(3, start + week))))
            assertEquals(1, repo.listings().single().repeats)
            assertTrue(promote(repo, receive(repo, message(4, start + week + 5 * minute))))
            assertEquals(1, repo.listings().single().repeats)
        }
    }

    @Test fun `duplicate window follows configured stability and includes its boundary`() {
        RoomDatabaseStore(Files.createTempDirectory("ad-repeat-window").resolve("test.db")).use { db ->
            db.settings.update { it.copy(stabilityWindowMinutes = 30) }
            val repo = CatalogRepository(db)
            assertTrue(promote(repo, receive(repo, message(1))))
            assertTrue(promote(repo, receive(repo, message(2, start + 30 * minute))))
            assertEquals(0, repo.listings().single().repeats)
            assertTrue(promote(repo, receive(repo, message(3, start + 60 * minute + 1))))
            assertEquals(1, repo.listings().single().repeats)
        }
    }

    @Test fun `replayed or edited merged sources do not increment or overwrite newer publications`() {
        RoomDatabaseStore(Files.createTempDirectory("ad-repeat-replay").resolve("test.db")).use { db ->
            val repo = CatalogRepository(db)
            assertTrue(promote(repo, receive(repo, message(1))))
            assertTrue(promote(repo, receive(repo, message(2, start + week))))
            assertNull(repo.source(-100, 1))
            assertFalse(promote(repo, receive(repo, message(1))))
            assertEquals(1, repo.listings().single().repeats)
            val original = message(1)
            assertTrue(promote(repo, receive(repo,
                original.copy(text = original.text + "\nБалкон", sourceEditedAt = start + 2 * week))))
            assertEquals(1, repo.listings().single().repeats)
        }
    }

    @Test fun `editing to a new key resets repeats but merging into an existing key preserves its count`() {
        RoomDatabaseStore(Files.createTempDirectory("ad-repeat-key").resolve("test.db")).use { db ->
            val repo = CatalogRepository(db)
            assertTrue(promote(repo, receive(repo, message(1))))
            assertTrue(promote(repo, receive(repo, message(2, start + week))))
            val repriced = message(2, start + week, price = 79000).copy(sourceEditedAt = start + 2 * week)
            assertTrue(promote(repo, receive(repo, repriced)))
            assertEquals(0, repo.listings().single().repeats)
            assertTrue(promote(repo, receive(repo, message(3, start + 3 * week))))
            assertTrue(promote(repo, receive(repo, message(4, start + 4 * week))))
            assertEquals(1, repo.listings().single { it.price == 80000L }.repeats)
            val merge = repriced.copy(text = message(2).text, sourceEditedAt = start + 5 * week)
            assertTrue(promote(repo, receive(repo, merge)))
            assertEquals(1, repo.listings().single().repeats)
        }
    }

    @Test fun `v36 migration adds zero repeats and preserves catalog inbox and repost status`() {
        val path = Files.createTempDirectory("ad-repeats-migration").resolve("test.db")
        RoomDatabaseStore(path).use { db ->
            val repo = CatalogRepository(db)
            assertTrue(promote(repo, receive(repo, message(1))))
            repo.finishRepost(repo.listings().single().id, RepostDestination.TIKTOK,
                RepostStatus.Published, processingTime)
        }
        BundledSQLiteDriver().open(path.toString()).use {
            it.execSQL("ALTER TABLE adsTab DROP COLUMN repeats")
            it.execSQL("PRAGMA user_version=36")
        }
        repeat(2) {
            RoomDatabaseStore(path).use { db ->
                val repo = CatalogRepository(db)
                val listing = repo.listings().single()
                assertEquals(0, listing.repeats)
                assertEquals("PUBLISHED", listing.repostTikTok)
                assertEquals(80000L, listing.price)
                assertEquals(IncomingStatus.Promoted, repo.incoming().single().status)
            }
        }
    }
}
