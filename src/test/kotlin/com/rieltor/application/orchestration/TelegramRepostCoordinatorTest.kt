package com.rieltor.application.orchestration

import ch.qos.logback.classic.Logger
import ch.qos.logback.core.read.ListAppender
import com.rieltor.application.model.RepostFlowState
import com.rieltor.application.model.SkipReason
import com.rieltor.application.model.TelegramSourceState
import com.rieltor.application.port.PhotoRepostHandler
import com.rieltor.application.port.TelegramMessageSource
import com.rieltor.application.usecase.RepostPublishException
import com.rieltor.domain.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class TelegramRepostCoordinatorTest {
    @Test
    fun `observes source and publishes flow state`() = runBlocking {
        val source = FakeTelegramMessageSource()
        val coordinator = TelegramRepostCoordinator(
            source,
            PhotoRepostHandler {
                RepostResult.Published(listOf(PublishReceipt("publish-17", "Ірина", "SELF_ONLY")))
            },
        )

        try {
            coordinator.start()
            source.emit(message(17))

            val state = withTimeout(1_000) {
                coordinator.state.first { it is RepostFlowState.Published }
            }

            assertEquals(TelegramSourceState.Ready, coordinator.sourceState.value)
            assertEquals(RepostFlowState.Published(17, "publish-17"), state)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `maps duplicate result to skipped state`() = runBlocking {
        val source = FakeTelegramMessageSource()
        val coordinator = TelegramRepostCoordinator(source, PhotoRepostHandler { RepostResult.Duplicate })

        try {
            coordinator.start()
            source.emit(message(18))

            val state = withTimeout(1_000) {
                coordinator.state.first { it is RepostFlowState.Skipped }
            }

            val skipped = assertIs<RepostFlowState.Skipped>(state)
            assertEquals(18, skipped.updateId)
            assertEquals(SkipReason.DUPLICATE, skipped.reason)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `converts handler failure to observable failed state`() = runBlocking {
        val source = FakeTelegramMessageSource()
        val coordinator = TelegramRepostCoordinator(
            source,
            PhotoRepostHandler { error("TikTok unavailable") },
        )

        try {
            coordinator.start()
            source.emit(message(19))

            val state = withTimeout(1_000) {
                coordinator.state.first { it is RepostFlowState.Failed }
            }

            assertEquals(RepostFlowState.Failed(19, "TikTok unavailable"), state)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `logs expected publish failure in one line without stack trace`() = runBlocking {
        val source = FakeTelegramMessageSource()
        val coordinator = TelegramRepostCoordinator(
            source,
            PhotoRepostHandler {
                throw RepostPublishException(
                    listOf(RepostFailure(RepostDestination.TIKTOK, "picture_size_check_failed"))
                )
            },
        )
        val logger = LoggerFactory.getLogger(TelegramRepostCoordinator::class.java) as Logger
        val appender = ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
        logger.addAppender(appender)

        try {
            coordinator.start()
            source.emit(message(22))

            withTimeout(1_000) {
                coordinator.state.first { it is RepostFlowState.Failed }
            }

            val event = appender.list.first {
                it.formattedMessage.startsWith("Failed to repost Telegram message.") &&
                    it.formattedMessage.contains("updateId=22")
            }
            assertTrue(event.formattedMessage.contains("picture_size_check_failed"))
            assertEquals(null, event.throwableProxy)
        } finally {
            coordinator.close()
            logger.detachAppender(appender)
        }
    }

    @Test
    fun `logs queue and remaining retry delay in periodic diagnostics`() = runBlocking {
        val source = FakeTelegramMessageSource()
        val coordinator = TelegramRepostCoordinator(
            source = source,
            repostHandler = PhotoRepostHandler { error("TikTok unavailable") },
            retryDelayMillis = 1_000,
            diagnosticsIntervalMillis = 10,
        )
        val logger = LoggerFactory.getLogger(TelegramRepostCoordinator::class.java) as Logger
        val appender = ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
        logger.addAppender(appender)

        try {
            coordinator.start()
            source.emit(message(23))

            withTimeout(1_000) {
                while (appender.list.none { event ->
                    event.formattedMessage.startsWith("Telegram repost coordinator status.") &&
                        event.formattedMessage.contains("claimedUpdateId=23") &&
                        event.formattedMessage.contains("waitingFor=retry delay") &&
                        event.formattedMessage.contains("remainingMinutes=1")
                }) delay(10)
            }
        } finally {
            coordinator.close()
            logger.detachAppender(appender)
        }
    }

    @Test
    fun `processes incoming messages in FIFO order`() = runBlocking {
        val source = FakeTelegramMessageSource()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
        val activeHandlers = AtomicInteger()
        val maximumActiveHandlers = AtomicInteger()
        val processed = mutableListOf<Long>()
        val coordinator = TelegramRepostCoordinator(
            source,
            PhotoRepostHandler { listing ->
                val active = activeHandlers.incrementAndGet()
                maximumActiveHandlers.updateAndGet { maximum -> maxOf(maximum, active) }
                if (listing.updateId == 20L) {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                }
                synchronized(processed) { processed += listing.updateId }
                activeHandlers.decrementAndGet()
                if (processed.size == 3) completed.complete(Unit)
                RepostResult.Duplicate
            },
        )

        try {
            coordinator.start()
            source.emit(message(20))
            withTimeout(1_000) { firstStarted.await() }
            source.emit(message(21))
            source.emit(message(22))
            releaseFirst.complete(Unit)

            withTimeout(1_000) { completed.await() }

            assertEquals(listOf(20L, 21L, 22L), processed)
            assertEquals(1, maximumActiveHandlers.get())
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `drops oldest pending message when FIFO queue is full`() = runBlocking {
        val source = FakeTelegramMessageSource()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val oldestClosed = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
        val processed = mutableListOf<Long>()
        val coordinator = TelegramRepostCoordinator(
            source = source,
            repostHandler = PhotoRepostHandler { listing ->
                if (listing.updateId == 1L) {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                }
                synchronized(processed) { processed += listing.updateId }
                listing.photos.forEach { it.content.close() }
                if (processed.size == 3) completed.complete(Unit)
                RepostResult.Duplicate
            },
            queueCapacity = 2,
        )

        try {
            coordinator.start()
            source.emit(message(1))
            withTimeout(1_000) { firstStarted.await() }
            source.emit(message(2, oldestClosed))
            source.emit(message(3))
            source.emit(message(4))

            withTimeout(1_000) { oldestClosed.await() }
            releaseFirst.complete(Unit)
            withTimeout(1_000) { completed.await() }

            assertEquals(listOf(1L, 3L, 4L), processed)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `closes source and exposes failure when startup fails`() {
        val source = FakeTelegramMessageSource(failOnStart = true)
        val coordinator = TelegramRepostCoordinator(source, PhotoRepostHandler { RepostResult.Duplicate })

        val error = assertFailsWith<IllegalStateException> { coordinator.start() }

        assertEquals("TDLib startup failed", error.message)
        assertTrue(source.closed)
        assertEquals(RepostFlowState.Failed(null, "TDLib startup failed"), coordinator.state.value)
    }

    private fun message(updateId: Long, closed: CompletableDeferred<Unit>? = null) = TelegramListing(
        updateId = updateId,
        chatId = -1001,
        messageThreadId = 5,
        caption = "Квартира\nАдреса: вул. Соборна 1\nЦіна: 90000${'$'}\nhttps://drive.google.com/drive/folders/example",
        photos = closed?.let {
            listOf(TelegramPhoto("photo.jpg", object : ByteArrayInputStream(byteArrayOf(1)) {
                override fun close() {
                    super.close()
                    it.complete(Unit)
                }
            }))
        }.orEmpty(),
        googleDriveLinks = listOf("https://drive.google.com/drive/folders/example"),
        normalizedPrice = "90000:USD",
    )

    private class FakeTelegramMessageSource(
        private val failOnStart: Boolean = false,
    ) : TelegramMessageSource {
        private val channel = Channel<TelegramListing>(Channel.UNLIMITED)
        private val mutableState = MutableStateFlow<TelegramSourceState>(TelegramSourceState.Stopped)

        override val messages: Flow<TelegramListing> = channel.receiveAsFlow()
        override val state: StateFlow<TelegramSourceState> = mutableState
        var closed = false
            private set

        override fun start() {
            if (failOnStart) error("TDLib startup failed")
            mutableState.value = TelegramSourceState.Ready
        }

        suspend fun emit(message: TelegramListing) {
            channel.send(message)
        }

        override fun close() {
            closed = true
            channel.close()
            mutableState.value = TelegramSourceState.Stopped
        }
    }
}
