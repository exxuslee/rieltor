package com.rieltor.application

import com.rieltor.domain.model.PublishReceipt
import com.rieltor.domain.model.RepostResult
import com.rieltor.domain.model.StoredMedia
import com.rieltor.domain.model.TelegramPhotoMessage
import com.rieltor.domain.port.PhotoPublisher
import com.rieltor.domain.port.PostJobRepository
import com.rieltor.domain.port.PublicMediaStorage
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PhotoRepostServiceTest {
    @Test
    fun `publishes photo from allowed sender once`() = runBlocking {
        val jobs = FakeJobs()
        val publisher = FakePublisher()
        val service = PhotoRepostService(
            allowedSenderId = 530667295L,
            jobs = jobs,
            mediaStorage = FakeStorage(),
            publisher = publisher,
        )
        val message = message(updateId = 42, senderId = 530667295L)

        assertIs<RepostResult.Published>(service.handle(message))
        assertEquals(1, publisher.calls)
        assertEquals("publish-42", jobs.publishedId)

        assertEquals(RepostResult.Duplicate, service.handle(message(updateId = 42, senderId = 530667295L)))
        assertEquals(1, publisher.calls)
    }

    @Test
    fun `ignores all other senders`() = runBlocking {
        val publisher = FakePublisher()
        val service = PhotoRepostService(530667295L, FakeJobs(), FakeStorage(), publisher)

        assertEquals(RepostResult.IgnoredSender, service.handle(message(1, 123L)))
        assertEquals(0, publisher.calls)
    }

    private fun message(updateId: Long, senderId: Long) = TelegramPhotoMessage(
        updateId = updateId,
        senderId = senderId,
        chatId = senderId,
        caption = "Квартира в Ірпені",
        fileName = "photo.jpg",
        content = ByteArrayInputStream(byteArrayOf(1, 2, 3)),
    )

    private class FakeStorage : PublicMediaStorage {
        override fun store(fileName: String, content: InputStream) =
            StoredMedia("https://api.example/media/photo.jpg", "photo.jpg")
    }

    private class FakePublisher : PhotoPublisher {
        var calls = 0
        override suspend fun publish(photoUrl: String, caption: String?): PublishReceipt {
            calls++
            return PublishReceipt("publish-42", "Ірина", "SELF_ONLY")
        }
    }

    private class FakeJobs : PostJobRepository {
        private val started = mutableSetOf<Long>()
        var publishedId: String? = null
        override fun tryStart(telegramUpdateId: Long) = started.add(telegramUpdateId)
        override fun markPublished(telegramUpdateId: Long, publishId: String) {
            publishedId = publishId
        }
        override fun markFailed(telegramUpdateId: Long, error: String) = Unit
    }
}
