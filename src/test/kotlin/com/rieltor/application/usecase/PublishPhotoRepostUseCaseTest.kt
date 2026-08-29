package com.rieltor.application.usecase

import com.rieltor.application.service.TelegramRepostTracker
import com.rieltor.domain.model.PublishReceipt
import com.rieltor.domain.model.ReceivedTelegramMessage
import com.rieltor.domain.model.RepostResult
import com.rieltor.domain.model.StoredMedia
import com.rieltor.domain.model.TelegramPhoto
import com.rieltor.domain.model.TelegramPhotoMessage
import com.rieltor.domain.model.TelegramMonitoredTopic
import com.rieltor.domain.model.TelegramMessageRegistration
import com.rieltor.domain.repository.ExternalPhotoSource
import com.rieltor.domain.repository.PhotoPublisher
import com.rieltor.domain.repository.PublicMediaStorage
import com.rieltor.domain.repository.TelegramRepostRepository
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PublishPhotoRepostUseCaseTest {
    @Test
    fun `publishes photo from monitored chat once`() = runBlocking {
        val jobs = FakeJobs()
        val publisher = FakePublisher()
        val service = PublishPhotoRepostUseCase(
            repostTracker = TelegramRepostTracker(jobs),
            mediaStorage = FakeStorage(),
            publisher = publisher,
            allowedSources = setOf(TelegramMonitoredTopic(MONITORED_CHAT_ID, MONITORED_THREAD_ID)),
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
        val service = PublishPhotoRepostUseCase(
            repostTracker = TelegramRepostTracker(FakeJobs()),
            mediaStorage = FakeStorage(),
            publisher = publisher,
            allowedSources = setOf(TelegramMonitoredTopic(MONITORED_CHAT_ID, MONITORED_THREAD_ID)),
        )
        val message = TelegramPhotoMessage(
            updateId = 43,
            chatId = MONITORED_CHAT_ID,
            messageThreadId = MONITORED_THREAD_ID,
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
        assertEquals(
            """🏠 Альбом квартири

📞 066-372-71-02 Ірина

#нерухомість #продажнерухомості #квартира #ІринаЛіннік""",
            publisher.publishedCaptions.single(),
        )
    }

    @Test
    fun `adds google drive photos from caption to the same post`() = runBlocking {
        val publisher = FakePublisher()
        val service = PublishPhotoRepostUseCase(
            repostTracker = TelegramRepostTracker(FakeJobs()),
            mediaStorage = FakeStorage(),
            publisher = publisher,
            externalPhotoSource = FakeExternalPhotoSource(),
            allowedSources = setOf(TelegramMonitoredTopic(MONITORED_CHAT_ID, MONITORED_THREAD_ID)),
        )
        val message = TelegramPhotoMessage(
            updateId = 44,
            chatId = MONITORED_CHAT_ID,
            messageThreadId = MONITORED_THREAD_ID,
            caption = "Будинок\nhttps://drive.google.com/drive/folders/folder123456",
            photos = listOf(TelegramPhoto("telegram.jpg", ByteArrayInputStream(byteArrayOf(1)))),
        )

        assertIs<RepostResult.Published>(service.handle(message))
        assertEquals(3, publisher.publishedUrls.single().size)
        assertEquals(false, publisher.publishedCaptions.single()?.contains("drive.google.com"))
    }

    @Test
    fun `ignores all other chats`() = runBlocking {
        val publisher = FakePublisher()
        val service = PublishPhotoRepostUseCase(
            repostTracker = TelegramRepostTracker(FakeJobs()),
            mediaStorage = FakeStorage(),
            publisher = publisher,
            allowedSources = setOf(TelegramMonitoredTopic(MONITORED_CHAT_ID, MONITORED_THREAD_ID)),
        )

        assertEquals(RepostResult.IgnoredSource, service.handle(message(1, 123L)))
        assertEquals(0, publisher.calls)
    }

    @Test
    fun `ignores text without photos or supported external link`() = runBlocking {
        val publisher = FakePublisher()
        val service = PublishPhotoRepostUseCase(
            repostTracker = TelegramRepostTracker(FakeJobs()),
            mediaStorage = FakeStorage(),
            publisher = publisher,
            externalPhotoSource = FakeExternalPhotoSource(),
            allowedSources = setOf(TelegramMonitoredTopic(MONITORED_CHAT_ID, MONITORED_THREAD_ID)),
        )
        val message = TelegramPhotoMessage(
            updateId = 5,
            chatId = MONITORED_CHAT_ID,
            messageThreadId = MONITORED_THREAD_ID,
            caption = "Звичайне текстове повідомлення",
            photos = emptyList(),
        )

        assertEquals(RepostResult.IgnoredContent, service.handle(message))
        assertEquals(0, publisher.calls)
    }

    @Test
    fun `publishes photo from configured monitored chat`() = runBlocking {
        val publisher = FakePublisher()
        val service = PublishPhotoRepostUseCase(
            repostTracker = TelegramRepostTracker(FakeJobs()),
            mediaStorage = FakeStorage(),
            publisher = publisher,
            allowedSources = setOf(TelegramMonitoredTopic(MONITORED_CHAT_ID, MONITORED_THREAD_ID)),
        )

        val result = service.handle(message(2))

        assertIs<RepostResult.Published>(result)
        assertEquals(1, publisher.calls)
    }

    @Test
    fun `ignores photo from unmonitored forum topic in monitored chat`() = runBlocking {
        val publisher = FakePublisher()
        val service = PublishPhotoRepostUseCase(
            repostTracker = TelegramRepostTracker(FakeJobs()),
            mediaStorage = FakeStorage(),
            publisher = publisher,
            allowedSources = setOf(TelegramMonitoredTopic(MONITORED_CHAT_ID, MONITORED_THREAD_ID)),
        )

        val result = service.handle(message(updateId = 3, messageThreadId = 999999L))

        assertEquals(RepostResult.IgnoredSource, result)
        assertEquals(0, publisher.calls)
    }

    @Test
    fun `does not publish repeated listing with a new telegram update id`() = runBlocking {
        val publisher = FakePublisher()
        val service = PublishPhotoRepostUseCase(
            repostTracker = TelegramRepostTracker(FakeJobs()),
            mediaStorage = FakeStorage(),
            publisher = publisher,
            allowedSources = setOf(TelegramMonitoredTopic(MONITORED_CHAT_ID, MONITORED_THREAD_ID)),
        )
        val first = message(
            updateId = 10,
            caption = "Вул. Мечнікова, 10\nЦіна: 175 000 ${'$'}",
        )
        val repeated = message(
            updateId = 11,
            caption = "вулиця Мечнікова 10\nЦіна 175000 USD",
        )

        assertIs<RepostResult.Published>(service.handle(first))
        assertEquals(RepostResult.Duplicate, service.handle(repeated))
        assertEquals(1, publisher.calls)
    }

    private fun message(
        updateId: Long,
        chatId: Long = MONITORED_CHAT_ID,
        messageThreadId: Long = MONITORED_THREAD_ID,
        caption: String = "Квартира в Ірпені",
    ) = TelegramPhotoMessage(
        updateId = updateId,
        chatId = chatId,
        messageThreadId = messageThreadId,
        caption = caption,
        photos = listOf(TelegramPhoto("photo.jpg", ByteArrayInputStream(byteArrayOf(1, 2, 3)))),
    )

    private companion object {
        const val MONITORED_CHAT_ID = -1002681732909L
        const val MONITORED_THREAD_ID = 5242880L
    }

    private class FakeStorage : PublicMediaStorage {
        override fun store(fileName: String, content: InputStream) =
            StoredMedia("https://api.example/media/photo.jpg", "photo.jpg")
    }

    private class FakePublisher : PhotoPublisher {
        var calls = 0
        val publishedUrls = mutableListOf<List<String>>()
        val publishedCaptions = mutableListOf<String?>()
        override suspend fun publish(photoUrls: List<String>, caption: String?): PublishReceipt {
            calls++
            publishedUrls += photoUrls
            publishedCaptions += caption
            return PublishReceipt("publish-42", "Ірина", "SELF_ONLY")
        }
    }

    private class FakeExternalPhotoSource : ExternalPhotoSource {
        override fun containsLink(text: String?) = text?.contains("drive.google.com") == true

        override suspend fun downloadPhotos(text: String?, limit: Int): List<TelegramPhoto> = listOf(
            TelegramPhoto("drive-one.jpg", ByteArrayInputStream(byteArrayOf(2))),
            TelegramPhoto("drive-two.jpg", ByteArrayInputStream(byteArrayOf(3))),
        ).take(limit)
    }

    private class FakeJobs : TelegramRepostRepository {
        private val messages = mutableMapOf<Long, ReceivedTelegramMessage>()
        private val activeKeys = mutableMapOf<com.rieltor.domain.model.TelegramRepostKey, Long>()
        var publishedId: String? = null

        override fun register(message: ReceivedTelegramMessage): TelegramMessageRegistration {
            messages[message.updateId]?.let {
                return TelegramMessageRegistration.Duplicate(message.updateId)
            }
            messages[message.updateId] = message
            val key = message.repostKey
            val original = key?.let(activeKeys::get)
            if (original != null) return TelegramMessageRegistration.Duplicate(original)
            if (key != null) activeKeys[key] = message.updateId
            return TelegramMessageRegistration.Accepted(key)
        }

        override fun markRepostPublished(telegramUpdateId: Long, publishId: String) {
            publishedId = publishId
        }

        override fun markRepostFailed(telegramUpdateId: Long, error: String) {
            messages.remove(telegramUpdateId)?.repostKey?.let(activeKeys::remove)
        }
    }
}
