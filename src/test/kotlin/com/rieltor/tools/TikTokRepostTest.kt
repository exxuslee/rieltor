package com.rieltor.tools

import com.rieltor.application.worker.RepostWorker
import com.rieltor.domain.model.*
import com.rieltor.domain.repository.PhotoPublisher
import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.model.AdEntity
import com.rieltor.infrastructure.database.repository.CatalogRepository
import com.rieltor.infrastructure.media.LocalPublicMediaStorage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.*

class TikTokRepostTest {
    @Test
    fun `id must be a single positive long`() {
        assertEquals(42L, parseTikTokRepostId(arrayOf("42")))
        for (args in listOf(emptyArray(), arrayOf("abc"), arrayOf("0"), arrayOf("-1"),
            arrayOf("1", "2"), arrayOf("9223372036854775808"))) {
            assertFailsWith<IllegalArgumentException> { parseTikTokRepostId(args) }
        }
    }

    @Test
    fun `manual repost targets exact id preserves history and reports uncertain sends`() = runBlocking {
        val directory = Files.createTempDirectory("manual-tiktok")
        RoomDatabaseStore(directory.resolve("test.db")).use { db ->
            db.settings.update { it.copy(tiktokEnabled = false, threadsEnabled = true) }
            val repo = CatalogRepository(db)
            val media = LocalPublicMediaStorage(directory.resolve("media"), "https://media.example")
            val fileName = "00000000-0000-0000-0000-000000000001.jpg"
            Files.write(directory.resolve("media").resolve(fileName), byteArrayOf(1))
            fun add(message: Long, status: ListingStatus = ListingStatus.Active, photos: String =
                Json.encodeToString(listOf(CatalogPhoto(fileName, "file", "1", 1, 1, "hash")))) = repo.save(
                AdEntity(adId = "ad$message", chatId = -1, messageId = message, messageThreadId = 1,
                    sourceRevision = "1", title = "Listing $message", price = 10000, currency = "USD",
                    timestamp = message, status = status, photos = photos)
            )
            val id = add(1)
            val newerId = add(2)
            val hiddenId = add(3, ListingStatus.Hidden)
            val noPhotosId = add(4, photos = "[]")
            var calls = 0
            var fail = false
            var draft = false
            val publisher = object : PhotoPublisher {
                override val destination = RepostDestination.TIKTOK
                override val maxPhotoCount = 10
                override suspend fun publish(photoUrls: List<String>, caption: String?): PublishReceipt {
                    calls++
                    assertEquals(listOf("https://media.example/media/$fileName"), photoUrls)
                    assertTrue(caption.orEmpty().contains("Listing 1"))
                    if (fail) error("Response lost")
                    return PublishReceipt("publish-$calls", "creator", if (draft) "DRAFT" else "SELF_ONLY")
                }
            }
            RepostWorker(repo, db.settings, listOf(publisher), media).use { worker ->
                assertFailsWith<IllegalStateException> { worker.repostTikTok(999999) }
                assertFailsWith<IllegalStateException> { worker.repostTikTok(hiddenId) }
                assertFailsWith<IllegalStateException> { worker.repostTikTok(noPhotosId) }
                db.settings.update { it.copy(blockedUntil = Long.MAX_VALUE) }
                assertEquals(0, calls)
                worker.runOnce()
                assertEquals(0, calls)
                assertEquals(RepostStatus.Published, worker.repostTikTok(id).status)
                assertEquals(Long.MAX_VALUE, db.settings.snapshot().blockedUntil)
                draft = true
                assertEquals(RepostStatus.DeliveredDraft, worker.repostTikTok(id).status)
                assertEquals(2, repo.publication(repo.repost(id)!!, publisher.destination).attempts.size)
                assertEquals(RepostStatus.Pending, repo.status(repo.repost(newerId)!!, publisher.destination))
                assertEquals(RepostStatus.Pending, repo.status(repo.repost(id)!!, RepostDestination.THREADS))
                fail = true
                assertFailsWith<IllegalStateException> { worker.repostTikTok(id) }
                assertEquals(RepostStatus.Unknown, repo.status(repo.repost(id)!!, publisher.destination))
                assertFailsWith<IllegalStateException> { worker.repostTikTok(id) }
                assertEquals(3, calls)
            }
        }
    }

    @Test
    fun `manual claim blocks concurrent attempts and allows retry after known failure`() {
        val directory = Files.createTempDirectory("manual-tiktok-claim")
        RoomDatabaseStore(directory.resolve("test.db")).use { db ->
            val repo = CatalogRepository(db)
            val id = repo.save(AdEntity(adId = "ad", chatId = -1, messageId = 1, messageThreadId = 1,
                sourceRevision = "1", title = "Listing", price = 10000, currency = "USD",
                timestamp = 1, status = ListingStatus.Active))
            val destination = RepostDestination.TIKTOK
            val attempt = repo.prepareManual(id, destination, 1)
            for (status in listOf(RepostStatus.Prepared, RepostStatus.Sending,
                RepostStatus.AwaitingConfirmation, RepostStatus.Unknown)) {
                repo.changeAttempt(id, destination, attempt, 2) { it.copy(status = status) }
                assertFailsWith<IllegalStateException> { repo.prepareManual(id, destination, 3) }
            }
            repo.changeAttempt(id, destination, attempt, 4) { it.copy(status = RepostStatus.Failed) }
            assertNotEquals(attempt, repo.prepareManual(id, destination, 5))
        }
    }
}
