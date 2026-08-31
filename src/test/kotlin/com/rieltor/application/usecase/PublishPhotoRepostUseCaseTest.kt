package com.rieltor.application.usecase

import com.rieltor.application.service.TelegramRepostTracker
import com.rieltor.domain.model.*
import com.rieltor.domain.repository.ExternalPhotoSource
import com.rieltor.domain.repository.PhotoPublisher
import com.rieltor.domain.repository.PublicMediaStorage
import com.rieltor.domain.repository.TelegramRepostRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class PublishPhotoRepostUseCaseTest {
    @Test
    fun `publishes photo from monitored chat once`() = runBlocking {
        val jobs = FakeJobs()
        val publisher = FakePublisher()
        val service = PublishPhotoRepostUseCase(
            repostTracker = TelegramRepostTracker(jobs),
            mediaStorage = FakeStorage(),
            publishers = listOf(publisher),
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
        val storage = FakeStorage()
        val service = PublishPhotoRepostUseCase(
            repostTracker = TelegramRepostTracker(FakeJobs()),
            mediaStorage = storage,
            publishers = listOf(publisher),
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
        assertEquals("Альбом квартири", storage.textOverlays[0]?.title)
        assertEquals("🤙 066-372-71-02 Ірина", storage.textOverlays[0]?.contact)
        assertEquals(listOf(false, true, true), storage.textOverlays.map { it == null })
        assertEquals(
            """🏠 Альбом квартири

🤙 066-372-71-02 Ірина

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
            publishers = listOf(publisher),
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
    fun `caps drive downloads and publication for a small VM`() = runBlocking {
        val publisher = FakePublisher(maxPhotoCount = 35)
        val externalPhotos = CapturingExternalPhotoSource()
        val service = PublishPhotoRepostUseCase(
            repostTracker = TelegramRepostTracker(FakeJobs()),
            mediaStorage = FakeStorage(),
            publishers = listOf(publisher),
            externalPhotoSource = externalPhotos,
            allowedSources = setOf(TelegramMonitoredTopic(MONITORED_CHAT_ID, MONITORED_THREAD_ID)),
            maxPhotoCount = 10,
        )

        assertIs<RepostResult.Published>(service.handle(message(
            updateId = 441,
            caption = "Будинок\nhttps://drive.google.com/drive/folders/folder123456",
        )))

        assertEquals(9, externalPhotos.requestedLimit)
        assertEquals(10, publisher.publishedUrls.single().size)
    }

    @Test
    fun `publishes telegram photo when google drive contains no supported photos`() = runBlocking {
        val publisher = FakePublisher()
        val service = PublishPhotoRepostUseCase(
            repostTracker = TelegramRepostTracker(FakeJobs()),
            mediaStorage = FakeStorage(),
            publishers = listOf(publisher),
            externalPhotoSource = FailingExternalPhotoSource(),
            allowedSources = setOf(TelegramMonitoredTopic(MONITORED_CHAT_ID, MONITORED_THREAD_ID)),
        )
        val message = message(
            updateId = 45,
            caption = "Будинок\nhttps://drive.google.com/drive/folders/folder123456",
        )

        assertIs<RepostResult.Published>(service.handle(message))

        assertEquals(1, publisher.calls)
        assertEquals(1, publisher.publishedUrls.single().size)
    }

    @Test
    fun `keeps google drive failure when message has no telegram photo`() = runBlocking {
        val publisher = FakePublisher()
        val service = PublishPhotoRepostUseCase(
            repostTracker = TelegramRepostTracker(FakeJobs()),
            mediaStorage = FakeStorage(),
            publishers = listOf(publisher),
            externalPhotoSource = FailingExternalPhotoSource(),
            allowedSources = setOf(TelegramMonitoredTopic(MONITORED_CHAT_ID, MONITORED_THREAD_ID)),
        )
        val message = TelegramPhotoMessage(
            updateId = 46,
            chatId = MONITORED_CHAT_ID,
            messageThreadId = MONITORED_THREAD_ID,
            caption = "Будинок\nhttps://drive.google.com/drive/folders/folder123456",
            photos = emptyList(),
        )

        assertFailsWith<IllegalStateException> { service.handle(message) }
        assertEquals(0, publisher.calls)
    }

    @Test
    fun `ignores all other chats`() = runBlocking {
        val publisher = FakePublisher()
        val service = PublishPhotoRepostUseCase(
            repostTracker = TelegramRepostTracker(FakeJobs()),
            mediaStorage = FakeStorage(),
            publishers = listOf(publisher),
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
            publishers = listOf(publisher),
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
            publishers = listOf(publisher),
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
            publishers = listOf(publisher),
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
            publishers = listOf(publisher),
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

    @Test
    fun `one destination failure does not cancel another destination`() = runBlocking {
        val jobs = FakeJobs()
        val tikTok = FakePublisher()
        val threads = FakePublisher(
            destination = RepostDestination.THREADS,
            maxPhotoCount = 20,
            failure = IllegalStateException("Threads unavailable"),
        )
        val service = PublishPhotoRepostUseCase(
            repostTracker = TelegramRepostTracker(jobs),
            mediaStorage = FakeStorage(),
            publishers = listOf(tikTok, threads),
            allowedSources = setOf(TelegramMonitoredTopic(MONITORED_CHAT_ID, MONITORED_THREAD_ID)),
        )

        val result = assertIs<RepostResult.Published>(service.handle(message(20)))

        assertEquals(listOf(RepostDestination.TIKTOK), result.receipts.map { it.destination })
        assertEquals(RepostDestination.THREADS, result.failures.single().destination)
        assertTrue(result.failures.single().reason.contains("Threads unavailable"))
        assertEquals(1, tikTok.calls)
        assertEquals(1, threads.calls)
    }

    @Test
    fun `publishes destinations sequentially to limit resource pressure`() = runBlocking {
        val jobs = FakeJobs()
        val activeCalls = AtomicInteger(0)
        val maxActiveCalls = AtomicInteger(0)
        val publishers = listOf(RepostDestination.TIKTOK, RepostDestination.THREADS).map { destination ->
            object : PhotoPublisher {
                override val destination = destination
                override val maxPhotoCount = 20

                override suspend fun publish(photoUrls: List<String>, caption: String?): PublishReceipt {
                    val active = activeCalls.incrementAndGet()
                    maxActiveCalls.updateAndGet { previous -> maxOf(previous, active) }
                    delay(10)
                    activeCalls.decrementAndGet()
                    return PublishReceipt("publish-$destination", "Ірина", "SELF_ONLY", destination)
                }
            }
        }
        val service = PublishPhotoRepostUseCase(
            repostTracker = TelegramRepostTracker(jobs),
            mediaStorage = FakeStorage(),
            publishers = publishers,
            allowedSources = setOf(TelegramMonitoredTopic(MONITORED_CHAT_ID, MONITORED_THREAD_ID)),
        )

        val result = assertIs<RepostResult.Published>(service.handle(message(21)))

        assertEquals(2, result.receipts.size)
        assertEquals(1, maxActiveCalls.get())
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
        val textOverlays = mutableListOf<MediaTextOverlay?>()

        override fun store(fileName: String, content: InputStream, textOverlay: MediaTextOverlay?): StoredMedia {
            textOverlays += textOverlay
            return StoredMedia("https://api.example/media/photo.jpg", "photo.jpg")
        }
    }

    private class FakePublisher(
        override val destination: RepostDestination = RepostDestination.TIKTOK,
        override val maxPhotoCount: Int = 35,
        private val failure: Throwable? = null,
    ) : PhotoPublisher {
        var calls = 0
        val publishedUrls = mutableListOf<List<String>>()
        val publishedCaptions = mutableListOf<String?>()
        override suspend fun publish(photoUrls: List<String>, caption: String?): PublishReceipt {
            calls++
            failure?.let { throw it }
            publishedUrls += photoUrls
            publishedCaptions += caption
            return PublishReceipt("publish-42", "Ірина", "SELF_ONLY", destination)
        }
    }

    private class FakeExternalPhotoSource : ExternalPhotoSource {
        override fun containsLink(text: String?) = text?.contains("drive.google.com") == true

        override suspend fun downloadPhotos(text: String?, limit: Int): List<TelegramPhoto> = listOf(
            TelegramPhoto("drive-one.jpg", ByteArrayInputStream(byteArrayOf(2))),
            TelegramPhoto("drive-two.jpg", ByteArrayInputStream(byteArrayOf(3))),
        ).take(limit)
    }

    private class FailingExternalPhotoSource : ExternalPhotoSource {
        override fun containsLink(text: String?) = text?.contains("drive.google.com") == true

        override suspend fun downloadPhotos(text: String?, limit: Int): List<TelegramPhoto> =
            throw IllegalStateException("The Google Drive link contains no supported photos")
    }

    private class CapturingExternalPhotoSource : ExternalPhotoSource {
        var requestedLimit: Int? = null

        override fun containsLink(text: String?) = text?.contains("drive.google.com") == true

        override suspend fun downloadPhotos(text: String?, limit: Int): List<TelegramPhoto> {
            requestedLimit = limit
            return (1..limit).map { index ->
                TelegramPhoto("drive-$index.jpg", ByteArrayInputStream(byteArrayOf(index.toByte())))
            }
        }
    }

    private class FakeJobs : TelegramRepostRepository {
        private val messages = mutableMapOf<Long, ReceivedTelegramMessage>()
        private val activeKeys = mutableMapOf<Pair<RepostDestination, com.rieltor.domain.model.TelegramRepostKey>, Long>()
        private val publications = mutableSetOf<Pair<Long, RepostDestination>>()
        var publishedId: String? = null

        override fun register(message: ReceivedTelegramMessage, destination: RepostDestination): TelegramMessageRegistration {
            if (message.updateId to destination in publications) {
                return TelegramMessageRegistration.Duplicate(message.updateId)
            }
            messages[message.updateId] = message
            val key = message.repostKey
            val destinationKey = key?.let { destination to it }
            val original = destinationKey?.let(activeKeys::get)
            if (original != null) return TelegramMessageRegistration.Duplicate(original)
            if (destinationKey != null) activeKeys[destinationKey] = message.updateId
            publications += message.updateId to destination
            return TelegramMessageRegistration.Accepted(key)
        }

        override fun markRepostPublished(telegramUpdateId: Long, destination: RepostDestination, publishId: String) {
            publishedId = publishId
        }

        override fun markRepostFailed(telegramUpdateId: Long, destination: RepostDestination, error: String) {
            publications -= telegramUpdateId to destination
            messages[telegramUpdateId]?.repostKey?.let { activeKeys.remove(destination to it) }
        }
    }
}
