package com.rieltor.application.orchestration

import ch.qos.logback.classic.Logger
import ch.qos.logback.core.read.ListAppender
import com.rieltor.application.model.RepostFlowState
import com.rieltor.application.model.SkipReason
import com.rieltor.application.model.TelegramSourceState
import com.rieltor.application.port.PhotoRepostHandler
import com.rieltor.application.port.TelegramMessageSource
import com.rieltor.application.usecase.RepostPublishException
import com.rieltor.domain.model.PublishReceipt
import com.rieltor.domain.model.RepostDestination
import com.rieltor.domain.model.RepostFailure
import com.rieltor.domain.model.RepostResult
import com.rieltor.domain.model.TelegramPhotoMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
    fun `processes incoming messages independently`() = runBlocking {
        val source = FakeTelegramMessageSource()
        val completed = CompletableDeferred<Unit>()
        val activeHandlers = AtomicInteger()
        val maximumActiveHandlers = AtomicInteger()
        val processed = AtomicInteger()
        val coordinator = TelegramRepostCoordinator(
            source,
            PhotoRepostHandler {
                val active = activeHandlers.incrementAndGet()
                maximumActiveHandlers.updateAndGet { maximum -> maxOf(maximum, active) }
                delay(25)
                activeHandlers.decrementAndGet()
                if (processed.incrementAndGet() == 2) completed.complete(Unit)
                RepostResult.Duplicate
            },
        )

        try {
            coordinator.start()
            source.emit(message(20))
            source.emit(message(21))

            withTimeout(1_000) { completed.await() }

            assertEquals(2, maximumActiveHandlers.get())
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

    private fun message(updateId: Long) = TelegramPhotoMessage(
        updateId = updateId,
        chatId = -1001,
        messageThreadId = 5,
        caption = "Квартира",
        photos = emptyList(),
    )

    private class FakeTelegramMessageSource(
        private val failOnStart: Boolean = false,
    ) : TelegramMessageSource {
        private val channel = Channel<TelegramPhotoMessage>(Channel.UNLIMITED)
        private val mutableState = MutableStateFlow<TelegramSourceState>(TelegramSourceState.Stopped)

        override val messages: Flow<TelegramPhotoMessage> = channel.receiveAsFlow()
        override val state: StateFlow<TelegramSourceState> = mutableState
        var closed = false
            private set

        override fun start() {
            if (failOnStart) error("TDLib startup failed")
            mutableState.value = TelegramSourceState.Ready
        }

        suspend fun emit(message: TelegramPhotoMessage) {
            channel.send(message)
        }

        override fun close() {
            closed = true
            channel.close()
            mutableState.value = TelegramSourceState.Stopped
        }
    }
}
