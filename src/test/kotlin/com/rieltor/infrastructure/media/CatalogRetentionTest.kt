package com.rieltor.infrastructure.media

import com.rieltor.domain.model.CatalogPhoto
import com.rieltor.domain.model.SourceMessage
import com.rieltor.domain.service.CatalogAdsParser
import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.model.IncomingStatus
import com.rieltor.infrastructure.database.repository.CatalogRepository
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*
import com.rieltor.infrastructure.job.CleanupJob as MediaCleanupJob

class CatalogRetentionTest {
    private val now = Instant.parse("2026-09-16T12:00:00Z")
    private val cutoff = now.minus(Duration.ofDays(30)).toEpochMilli()
    private fun message(
        id: Long,
        date: Long,
        price: Int = 82000,
        drive: String = "https://drive.google.com/drive/folders/property"
    ) =
        SourceMessage(
            -100,
            id,
            20,
            text = "Квартира\nІрпінь\nЦіна: $price USD\n$drive",
            raw = "raw-$id",
            sourceCreatedAt = date,
            userId = 123
        )

    private fun photo(name: String) = CatalogPhoto(name, name, "v1", 10, 10, "hash")
    private fun promote(repo: CatalogRepository, message: SourceMessage, photos: List<CatalogPhoto>): Boolean {
        repo.receive(message, now.toEpochMilli(), 0)
        val rows = repo.incoming(message.chatId, message.messageId)
        repo.stage(rows, IncomingStatus.ReadyForMedia, now.toEpochMilli())
        val token = assertNotNull(repo.claim(rows, now.toEpochMilli()))
        repo.manifest(rows, token, photos, now.toEpochMilli())
        val parsed = CatalogAdsParser().parse(rows, "APARTMENT", now.toEpochMilli())
        return repo.promote(rows, token, parsed.copy(photos = Json.encodeToString(photos)), now.toEpochMilli())
    }

    @Test
    fun `expires by source date despite recent verification and recent file timestamp`() {
        val root = Files.createTempDirectory("catalog-expiry")
        val media = Files.createDirectory(root.resolve("media"))
        RoomDatabaseStore(root.resolve("test.db")).use { db ->
            val repo = CatalogRepository(db)
            val expired = message(1, cutoff - 1)
            promote(repo, expired, listOf(photo("expired.jpg")))
            Files.createFile(media.resolve("expired.jpg")) // mtime is new, source is old
            repo.stage(repo.incoming(expired.chatId, expired.messageId), IncomingStatus.Promoted, now.toEpochMilli())
            promote(
                repo,
                message(2, cutoff, price = 83000, drive = "https://drive.google.com/open?id=boundary"),
                listOf(photo("boundary.jpg"))
            )
            Files.createFile(media.resolve("boundary.jpg"))
            Files.setLastModifiedTime(media.resolve("boundary.jpg"), FileTime.fromMillis(0))
            MediaCleanupJob(media, clock = Clock.fixed(now, ZoneOffset.UTC), catalogRepository = repo).use {
                assertEquals(1, it.cleanNow())
                assertEquals(0, it.cleanNow())
            }
            assertNull(repo.source(-100, 1))
            assertEquals(2L, repo.listings().single().messageId)
            assertFalse(Files.exists(media.resolve("expired.jpg")))
            assertTrue(Files.exists(media.resolve("boundary.jpg")))
        }
    }

    @Test
    fun `weekly repost with same adId preserves publication history`() {
        val root = Files.createTempDirectory("catalog-repost")
        RoomDatabaseStore(root.resolve("test.db")).use { db ->
            val repo = CatalogRepository(db)
            promote(repo, message(1, cutoff - 100), listOf(photo("kept.jpg")))
            val old = repo.listings().single()
            repo.save(old.copy(tiktokRepostedAt = now.toEpochMilli(), tiktokStatus = "PUBLISHED"))
            val fresh = message(2, now.toEpochMilli(), 82000, "https://drive.google.com/open?id=property&usp=sharing")
            assertTrue(promote(repo, fresh, listOf(photo("kept.jpg"))))
            val current = repo.listings().single()
            assertEquals(old.id, current.id)
            assertEquals(82_000L, current.price)
            assertEquals(now.toEpochMilli(), current.sourceCreatedAt)
            assertNotNull(current.tiktokRepostedAt)
            assertNull(repo.source(-100, 1))
            assertEquals(listOf(photo("kept.jpg")), repo.cachedPhotos(current))
            assertFalse(promote(repo, message(3, cutoff - 1), listOf(photo("stale.jpg"))))
            assertEquals(current, repo.listings().single())
            assertNull(repo.source(-100, 3))
            assertEquals(setOf("kept.jpg"), repo.cleanExpired(cutoff).referenced)
        }
    }

    @Test
    fun `source edit renews listing but unchanged refresh does not`() {
        val root = Files.createTempDirectory("catalog-edit")
        RoomDatabaseStore(root.resolve("test.db")).use { db ->
            val repo = CatalogRepository(db)
            val source = message(1, cutoff - 1)
            promote(repo, source, emptyList())
            val edited = source.copy(text = source.text.replace("82000", "79000"), sourceEditedAt = now.toEpochMilli())
            assertTrue(promote(repo, edited, emptyList()))
            repo.receive(edited, now.toEpochMilli() + 10000, 0)
            assertEquals(now.toEpochMilli(), repo.listings().single().sourceCreatedAt)
            repo.cleanExpired(cutoff)
            assertEquals(1, repo.listings().size)
        }
    }

    @Test
    fun `cleanup removes terminal and expired incoming including legacy and protects shared files`() {
        val root = Files.createTempDirectory("catalog-inbox-cleanup")
        RoomDatabaseStore(root.resolve("test.db")).use { db ->
            val repo = CatalogRepository(db)
            promote(repo, message(1, cutoff - 1), listOf(photo("shared.jpg")))
            promote(
                repo,
                message(2, now.toEpochMilli(), drive = "https://drive.google.com/drive/folders/other"),
                listOf(photo("shared.jpg"))
            )
            val invalid = message(3, now.toEpochMilli())
            repo.receive(invalid, now.toEpochMilli(), 0)
            repo.stage(repo.incoming(invalid.chatId, invalid.messageId), IncomingStatus.NeedsReview, now.toEpochMilli())
            val legacy = repo.source(-100, 3)!!
            db.blocking {
                it.catalogDao().saveSource(
                    legacy.copy(
                        id = 0,
                        messageId = null,
                        status = IncomingStatus.MediaRetry,
                        sourceCreatedAt = cutoff - 1
                    )
                )
            }
            val retention = repo.cleanExpired(cutoff)
            assertEquals(setOf("shared.jpg"), retention.referenced)
            assertTrue(retention.removed.isEmpty())
            assertEquals(1, db.blocking { it.catalogDao().allSources() }.size)
            assertEquals(1, repo.listings().size)
        }
    }

    @Test
    fun `orphan sweep has grace for downloads and database cleanup works without media directory`() {
        val root = Files.createTempDirectory("catalog-orphans")
        RoomDatabaseStore(root.resolve("test.db")).use { db ->
            val repo = CatalogRepository(db)
            promote(repo, message(1, cutoff - 1), emptyList())
            val media = root.resolve("media")
            MediaCleanupJob(media, clock = Clock.fixed(now, ZoneOffset.UTC), catalogRepository = repo).use { job ->
                assertEquals(0, job.cleanNow())
                assertTrue(repo.listings().isEmpty())
                Files.createDirectory(media)
                Files.createFile(media.resolve("orphan.jpg"))
                Files.setLastModifiedTime(media.resolve("orphan.jpg"), FileTime.from(now.minus(Duration.ofHours(2))))
                Files.createFile(media.resolve("downloading.jpg"))
                Files.setLastModifiedTime(media.resolve("downloading.jpg"), FileTime.from(now))
                assertEquals(1, job.cleanNow())
                assertTrue(Files.exists(media.resolve("downloading.jpg")))
            }
        }
    }

    @Test
    fun `discard checks revision and removes invalid current source and listing`() {
        val root = Files.createTempDirectory("catalog-discard")
        RoomDatabaseStore(root.resolve("test.db")).use { db ->
            val repo = CatalogRepository(db)
            val source = message(1, now.toEpochMilli())
            promote(repo, source, emptyList())
            val stale = repo.incoming(source.chatId, source.messageId)
            repo.receive(
                source.copy(text = "Без ціни і посилання", sourceEditedAt = now.toEpochMilli() + 1),
                now.toEpochMilli(),
                0
            )
            assertFalse(repo.discard(stale))
            assertTrue(repo.discard(repo.incoming(source.chatId, source.messageId)))
            assertNull(repo.source(-100, 1))
            assertTrue(repo.listings().isEmpty())
        }
    }
}
