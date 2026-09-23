package com.rieltor.application.usecase

import com.rieltor.application.port.TelegramBotMessageSource
import com.rieltor.application.port.TelegramBotReplySender
import com.rieltor.application.port.Worker
import com.rieltor.application.worker.ReplyTgBotWorker
import com.rieltor.domain.model.TelegramBotIncomingMessage
import com.rieltor.domain.model.TelegramPhoto
import com.rieltor.domain.repository.ExternalPhotoSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class ReplyTgBotWorkerTest {
    @Test
    fun `worker lifecycle connects message processing and closes the bot source`() = runBlocking {
        val source = FakeMessageSource()
        val sender = CapturingReplySender()
        val worker: Worker = ReplyTgBotWorker(FakePhotoSource(1), sender, source)

        worker.start()
        try {
            requireNotNull(source.onMessage).invoke(incomingMessage())
            assertEquals(1, sender.texts.size)
            assertEquals(listOf(1), sender.photoBatchSizes)
        } finally {
            worker.close()
        }
        assertTrue(source.closed)
    }

    @Test
    fun `processes bot message immediately and sends groups of at most ten`() = runBlocking {
        val photoSource = FakePhotoSource(photoCount = 23)
        val sender = CapturingReplySender()
        val useCase = ReplyTgBotWorker(photoSource, sender, FakeMessageSource())

        withTimeout(1_000.milliseconds) {
            useCase.execute(incomingMessage())
        }

        assertEquals(100, photoSource.requestedLimit)
        assertEquals(listOf(10, 10, 3), sender.photoBatchSizes)
        assertEquals(1, sender.texts.size)
        assertEquals(9001L, sender.texts.single().chatId)
        assertEquals(77, sender.texts.single().replyToMessageId)
        assertTrue(sender.texts.single().text.contains("066-372-71-02 Ірина"))
        assertFalse(sender.texts.single().text.contains("0990852854"))
        assertFalse(sender.texts.single().text.contains("drive.google.com"))
        assertTrue(photoSource.streams.all(CloseTrackingInputStream::closed))
    }

    @Test
    fun `does not call Drive without a supported link and replies with an explanation`() = runBlocking {
        val photoSource = FakePhotoSource(photoCount = 1)
        val sender = CapturingReplySender()
        val useCase = ReplyTgBotWorker(photoSource, sender, FakeMessageSource())

        useCase.execute(incomingMessage(text = "Квартира в Ірпені\nЦіна 50000$"))

        assertEquals(null, photoSource.requestedLimit)
        assertTrue(sender.texts.single().text.contains("Google Drive"))
        assertTrue(sender.photoBatchSizes.isEmpty())
    }

    @Test
    fun `reports a Drive failure without sending a partial formatted reply`() = runBlocking {
        val sender = CapturingReplySender()
        val useCase = ReplyTgBotWorker(
            externalPhotoSource = object : ExternalPhotoSource {
                override suspend fun downloadPhotos(links: List<String>, limit: Int): List<TelegramPhoto> {
                    error("Drive unavailable")
                }
            },
            replySender = sender,
            messageSource = FakeMessageSource(),
        )

        useCase.execute(incomingMessage())

        assertEquals(1, sender.texts.size)
        assertTrue(sender.texts.single().text.contains("Не вдалося завантажити"))
        assertTrue(sender.photoBatchSizes.isEmpty())
    }

    private class FakeMessageSource : TelegramBotMessageSource {
        var onMessage: (suspend (TelegramBotIncomingMessage) -> Unit)? = null
        var closed = false

        override fun start(onMessage: suspend (TelegramBotIncomingMessage) -> Unit) {
            this.onMessage = onMessage
        }

        override fun close() {
            closed = true
            onMessage = null
        }
    }

    private fun incomingMessage(
        text: String = """
            Ірпінь
            Квартира з ремонтом
            вул. Соборна, 1
            Площа 42 м2
            Ціна 50000$
            0990852854 Олексій
            https://drive.google.com/drive/folders/exampleFolder
        """.trimIndent(),
    ) = TelegramBotIncomingMessage(
        chatId = 9001L,
        messageId = 77,
        messageThreadId = 12,
        text = text,
    )

    private class FakePhotoSource(photoCount: Int) : ExternalPhotoSource {
        val streams = List(photoCount) { CloseTrackingInputStream() }
        var requestedLimit: Int? = null

        override suspend fun downloadPhotos(links: List<String>, limit: Int): List<TelegramPhoto> {
            requestedLimit = limit
            return streams.take(limit).mapIndexed { index, stream ->
                TelegramPhoto("photo-$index.jpg", stream)
            }
        }
    }

    private class CapturingReplySender : TelegramBotReplySender {
        val texts = mutableListOf<TextCall>()
        val photoBatchSizes = mutableListOf<Int>()

        override suspend fun sendText(
            chatId: Long,
            messageThreadId: Int?,
            replyToMessageId: Int?,
            text: String,
        ) {
            texts += TextCall(chatId, messageThreadId, replyToMessageId, text)
        }

        override suspend fun sendPhotos(
            chatId: Long,
            messageThreadId: Int?,
            photos: List<TelegramPhoto>,
        ) {
            photoBatchSizes += photos.size
        }
    }

    private data class TextCall(
        val chatId: Long,
        val messageThreadId: Int?,
        val replyToMessageId: Int?,
        val text: String,
    )

    private class CloseTrackingInputStream : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }
}
