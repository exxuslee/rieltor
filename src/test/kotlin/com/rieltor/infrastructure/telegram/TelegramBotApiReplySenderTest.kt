package com.rieltor.infrastructure.telegram

import com.rieltor.domain.model.TelegramPhoto
import kotlinx.coroutines.runBlocking
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.io.ByteArrayInputStream
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TelegramBotApiReplySenderTest {
    @Test
    fun `uses sendPhoto for one image and sendMediaGroup for multiple images`() = runBlocking {
        val calls = mutableListOf<Any>()
        val telegramClient = Proxy.newProxyInstance(
            TelegramClient::class.java.classLoader,
            arrayOf(TelegramClient::class.java),
        ) { _, method, arguments ->
            if (method.name == "execute" && !arguments.isNullOrEmpty()) {
                val request = arguments[0]
                calls += request
                when (request) {
                    is SendPhoto -> Message()
                    is SendMediaGroup -> arrayListOf<Message>()
                    else -> null
                }
            } else {
                null
            }
        } as TelegramClient
        val sender = TelegramBotApiReplySender(telegramClient)

        sender.sendPhotos(42L, 7, photos(1))
        sender.sendPhotos(42L, 7, photos(10))

        val single = assertIs<SendPhoto>(calls[0])
        assertEquals("42", single.chatId)
        assertEquals(7, single.messageThreadId)
        val album = assertIs<SendMediaGroup>(calls[1])
        assertEquals("42", album.chatId)
        assertEquals(7, album.messageThreadId)
        assertEquals(10, album.medias.size)
    }

    private fun photos(count: Int): List<TelegramPhoto> = List(count) { index ->
        TelegramPhoto("image-$index.jpg", ByteArrayInputStream(byteArrayOf(index.toByte())))
    }
}
