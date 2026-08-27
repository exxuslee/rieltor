package com.rieltor.application

import com.rieltor.domain.model.PublishReceipt
import com.rieltor.domain.model.RepostResult
import com.rieltor.domain.model.StoredMedia
import com.rieltor.domain.model.TelegramPhoto
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
    fun `publishes photo from monitored chat once`() = runBlocking {
        val jobs = FakeJobs()
        val publisher = FakePublisher()
        val service = PhotoRepostService(
            jobs = jobs,
            mediaStorage = FakeStorage(),
            publisher = publisher,
            allowedSourceChatIds = setOf(MONITORED_CHAT_ID),
        )
        val message = message(updateId = 42)

        assertIs<RepostResult.Published>(service.handle(message))
        assertEquals(1, publisher.calls)
        assertEquals("publish-42", jobs.publishedId)

        assertEquals(RepostResult.Duplicate, service.handle(message(updateId = 42)))
        assertEquals(1, publisher.calls)
    }

    @Test
    fun `publishes all photos as one post`() = runBlocking {
        val publisher = FakePublisher()
        val service = PhotoRepostService(
            jobs = FakeJobs(),
            mediaStorage = FakeStorage(),
            publisher = publisher,
            allowedSourceChatIds = setOf(MONITORED_CHAT_ID),
        )
        val message = TelegramPhotoMessage(
            updateId = 43,
            chatId = MONITORED_CHAT_ID,
            caption = "Альбом квартири",
            photos = listOf(
                TelegramPhoto("first.jpg", ByteArrayInputStream(byteArrayOf(1))),
                TelegramPhoto("second.jpg", ByteArrayInputStream(byteArrayOf(2))),
                TelegramPhoto("third.jpg", ByteArrayInputStream(byteArrayOf(3))),
            ),
        )

        assertIs<RepostResult.Published>(service.handle(message))
        assertEquals(1, publisher.calls)
        assertEquals(3, publisher.publishedUrls.single().size)
    }

    @Test
    fun `ignores all other chats`() = runBlocking {
        val publisher = FakePublisher()
        val service = PhotoRepostService(
            jobs = FakeJobs(),
            mediaStorage = FakeStorage(),
            publisher = publisher,
            allowedSourceChatIds = setOf(MONITORED_CHAT_ID),
        )

        assertEquals(RepostResult.IgnoredSource, service.handle(message(1, 123L)))
        assertEquals(0, publisher.calls)
    }

    @Test
    fun `publishes photo from configured monitored chat`() = runBlocking {
        val publisher = FakePublisher()
        val service = PhotoRepostService(
            jobs = FakeJobs(),
            mediaStorage = FakeStorage(),
            publisher = publisher,
            allowedSourceChatIds = setOf(-1002681732909L),
        )

        val result = service.handle(message(2))

        assertIs<RepostResult.Published>(result)
        assertEquals(1, publisher.calls)
    }

    private fun message(updateId: Long, chatId: Long = MONITORED_CHAT_ID) = TelegramPhotoMessage(
        updateId = updateId,
        chatId = chatId,
        caption = "Квартира в Ірпені",
        photos = listOf(TelegramPhoto("photo.jpg", ByteArrayInputStream(byteArrayOf(1, 2, 3)))),
    )

    private companion object {
        const val MONITORED_CHAT_ID = -1002681732909L
    }

    private class FakeStorage : PublicMediaStorage {
        override fun store(fileName: String, content: InputStream) =
            StoredMedia("https://api.example/media/photo.jpg", "photo.jpg")
    }

    private class FakePublisher : PhotoPublisher {
        var calls = 0
        val publishedUrls = mutableListOf<List<String>>()
        override suspend fun publish(photoUrls: List<String>, caption: String?): PublishReceipt {
            calls++
            publishedUrls += photoUrls
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
