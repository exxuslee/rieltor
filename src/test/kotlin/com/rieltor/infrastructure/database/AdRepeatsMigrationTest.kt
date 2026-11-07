package com.rieltor.infrastructure.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.rieltor.domain.model.ListingStatus
import com.rieltor.domain.model.REPOST_SOURCE_CHAT_ID
import com.rieltor.domain.model.SourceMessage
import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.model.AdEntity
import com.rieltor.infrastructure.database.model.IncomingEntity
import com.rieltor.infrastructure.database.model.IncomingStatus
import com.rieltor.infrastructure.database.model.StatisticsEvent
import com.rieltor.infrastructure.database.repository.CatalogRepository
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class AdRepeatsMigrationTest {
    private val minute = 60_000L
    private val week = 7 * 24 * 60 * minute
    private val start = 1_000_000L

    private fun ad(id: Long, adId: String = "ad-$id", repeats: Int = 0) = AdEntity(
        id = id, adId = adId, chatId = REPOST_SOURCE_CHAT_ID, messageId = id, messageThreadId = 20,
        sourceRevision = "revision", title = "Продам квартиру", price = 80000, currency = "USD",
        timestamp = start, status = ListingStatus.Active, repeats = repeats,
    )

    private fun source(message: Long, created: Long, chat: Long = REPOST_SOURCE_CHAT_ID) = IncomingEntity(
        chatId = chat, messageId = message, messageThreadId = 20, userId = 123,
        rawText = "Квартира", sourceCreatedAt = created, contentHash = "hash-$message", verifyAfter = 0,
        status = IncomingStatus.Promoted,
    )

    private fun accepted(adId: String?, source: IncomingEntity, time: Long? = null, listingId: Long? = null) =
        StatisticsEvent("ACCEPTED", "${source.chatId}:${source.messageId}", source.chatId,
            source.messageId, listingId, adId, time, source.messageThreadId)

    /** Versions 37 and 38 have identical tables; only their data migration differs. */
    private fun oldDatabase(version: Int = 37, seed: (RoomDatabaseStore) -> Unit): Path {
        val path = Files.createTempDirectory("restore-ad-repeats").resolve("test.db")
        RoomDatabaseStore(path).use(seed)
        BundledSQLiteDriver().open(path.toString()).use {
            if (version == 36) it.execSQL("ALTER TABLE adsTab DROP COLUMN repeats")
            it.execSQL("PRAGMA user_version=$version")
        }
        return path
    }

    @Test fun `v37 restores weekly repeats across chats using source dates and preserves data`() = checkWeekly(37)

    @Test fun `v36 migration chain adds counter then restores history`() = checkWeekly(36)

    private fun checkWeekly(version: Int) {
        val original = ad(3).copy(timestamp = start + 2 * week, repostTikTok = "PUBLISHED", repostThreads = "FAILED")
        val sources = listOf(source(1, start), source(2, start + week, -200),
            source(3, start + 2 * week).copy(sourceEditedAt = start + 5 * week))
        // All posts can be processed together during a history import.
        val events = sources.map { accepted(original.adId, it, start + 10 * week, original.id) }
        val path = oldDatabase(version) { db ->
            CatalogRepository(db).save(original)
            db.blocking { room ->
                sources.forEach { room.catalogDao().saveSource(it) }
                events.forEach { room.statisticsDao().record(it) }
            }
        }
        repeat(2) { // A second open must not add the same history again.
            RoomDatabaseStore(path).use { db ->
                val repo = CatalogRepository(db)
                assertEquals(original.copy(repeats = 2), repo.listing(original.id))
                assertEquals(sources, repo.incoming().sortedBy { it.messageId }.map { it.copy(id = 0) })
                db.blocking { room ->
                    assertTrue(room.statisticsDao().counts(null).all { it.kind == "ACCEPTED" })
                    assertEquals(3L, room.statisticsDao().counts(null).sumOf { it.count })
                }
                assertNull(repo.nextRepost(false, true))
            }
        }
        // The regular pipeline continues from the restored value.
        RoomDatabaseStore(path).use { db ->
            val repo = CatalogRepository(db)
            val created = start + 3 * week
            repo.receive(SourceMessage(REPOST_SOURCE_CHAT_ID, 4, 20, "Квартира", "", created, userId = 123), created, 0)
            val row = assertNotNull(repo.source(REPOST_SOURCE_CHAT_ID, 4))
            assertTrue(repo.stage(row, IncomingStatus.ReadyForMedia, created))
            assertTrue(repo.claim(row, created))
            assertTrue(repo.promote(row, original.copy(id = 0, messageId = 4, timestamp = created), created))
            assertEquals(3, repo.listing(original.id)?.repeats)
        }
    }

    @Test fun `migration groups quick duplicates using configured sliding stability window`() {
        val original = ad(5)
        val sources = listOf(source(1, start), source(2, start + 5 * minute),
            source(3, start + 35 * minute), source(4, start + 65 * minute),
            source(5, start + 95 * minute + 1))
        val path = oldDatabase { db ->
            db.settings.update { it.copy(stabilityWindowMinutes = 30) }
            CatalogRepository(db).save(original)
            db.blocking { room ->
                sources.forEach { row ->
                    room.catalogDao().saveSource(row)
                    room.statisticsDao().record(accepted(original.adId, row))
                }
            }
        }
        RoomDatabaseStore(path).use { db ->
            assertEquals(1, CatalogRepository(db).listing(original.id)?.repeats)
        }
    }

    @Test fun `missing source dates never fall back to receipt processing or edit times`() {
        val path = oldDatabase { db ->
            val repo = CatalogRepository(db)
            repo.save(ad(1))
            repo.save(ad(10)) // No acceptance history at all.
            db.blocking { room ->
                val missing = source(2, start)
                val undated = source(3, 0).copy(sourceEditedAt = start + week)
                val known = source(1, start + 2 * week)
                listOf(undated, known).forEach { room.catalogDao().saveSource(it) }
                listOf(missing, undated, known).forEachIndexed { index, row ->
                    room.statisticsDao().record(accepted("ad-1", row, start + index * week))
                    room.statisticsDao().record(accepted("ad-1", row, start + index * week)
                        .copy(kind = "RECEIVED", adId = null))
                }
            }
        }
        RoomDatabaseStore(path).use { db ->
            assertTrue(CatalogRepository(db).listings().all { it.repeats == 0 })
        }
    }

    @Test fun `history matches ad identity not reused listing IDs and counts each source once`() {
        val path = oldDatabase { db ->
            val repo = CatalogRepository(db)
            repo.save(ad(1, "current-key"))
            db.blocking { room ->
                val first = source(1, start)
                val next = source(2, start + week)
                val unrelated = source(3, start + 2 * week)
                listOf(first, next, unrelated).forEach { room.catalogDao().saveSource(it) }
                val event = accepted("current-key", first, listingId = 999)
                room.statisticsDao().record(event)
                room.statisticsDao().record(event.copy(sourceKey = "legacy-alias"))
                room.statisticsDao().record(accepted("current-key", next, listingId = 999))
                room.statisticsDao().record(accepted("old-key", unrelated, listingId = 1))
                room.statisticsDao().record(accepted(null, unrelated, listingId = 1))
                room.statisticsDao().record(accepted("current-key", unrelated).copy(kind = "PROCESSED"))
            }
        }
        RoomDatabaseStore(path).use { db ->
            assertEquals(1, CatalogRepository(db).listing(1)?.repeats)
        }
    }

    @Test fun `migration never reduces or adds already counted repeats`() {
        val path = oldDatabase { db ->
            val repo = CatalogRepository(db)
            for ((id, repeats) in listOf(1L to 7, 2L to 1, 3L to 4)) repo.save(ad(id, repeats = repeats))
            db.blocking { room ->
                for (id in 1L..2L) {
                    val first = source(id * 10, start)
                    val next = source(id * 10 + 1, start + week)
                    for (row in listOf(first, next)) {
                        room.catalogDao().saveSource(row)
                        room.statisticsDao().record(accepted("ad-$id", row))
                    }
                }
            }
        }
        repeat(2) {
            RoomDatabaseStore(path).use { db ->
                val repo = CatalogRepository(db)
                assertEquals(listOf(7, 1, 4), (1L..3L).map { id -> repo.listing(id)?.repeats })
            }
        }
    }
}
