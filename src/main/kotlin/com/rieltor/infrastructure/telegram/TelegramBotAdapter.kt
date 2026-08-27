package com.rieltor.infrastructure.telegram

import com.rieltor.application.PhotoRepostService
import com.rieltor.domain.model.RepostResult
import com.rieltor.domain.model.TelegramPhotoMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer
import org.telegram.telegrambots.meta.api.methods.GetFile
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.Update

class TelegramBotAdapter(
    private val botToken: String,
    private val allowedSenderId: Long,
    private val repostService: PhotoRepostService,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val client = OkHttpTelegramClient(botToken)
    private val application = TelegramBotsLongPollingApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        application.registerBot(botToken, Consumer())
        logger.info("Telegram long polling started for allowed sender id={}", allowedSenderId)
    }

    override fun close() {
        scope.cancel()
        application.close()
    }

    private inner class Consumer : LongPollingSingleThreadUpdateConsumer {
        override fun consume(update: Update) {
            val message = update.message ?: return
            val senderId = message.from?.id ?: return
            if (senderId != allowedSenderId || !message.hasPhoto()) return

            scope.launch {
                runCatching {
                    val photo = message.photo.maxByOrNull { size ->
                        (size.fileSize ?: 0).toLong().takeIf { it > 0 }
                            ?: (size.width.toLong() * size.height.toLong())
                    } ?: return@launch
                    val telegramFile = client.execute(GetFile(photo.fileId))
                    val input = client.downloadFileAsStream(telegramFile)
                    val fileName = telegramFile.filePath?.substringAfterLast('/') ?: "telegram-photo.jpg"
                    repostService.handle(
                        TelegramPhotoMessage(
                            updateId = update.updateId.toLong(),
                            senderId = senderId,
                            chatId = message.chatId,
                            caption = message.caption,
                            fileName = fileName,
                            content = input,
                        )
                    )
                }.onSuccess { result ->
                    when (result) {
                        is RepostResult.Published -> sendStatus(
                            message.chatId,
                            "Фото передано в TikTok (${result.receipt.creatorName}). " +
                                "publish_id: ${result.receipt.publishId}; privacy: ${result.receipt.privacyLevel}",
                        )
                        RepostResult.Duplicate -> sendStatus(message.chatId, "Это сообщение уже обработано.")
                        RepostResult.IgnoredSender -> Unit
                    }
                }.onFailure { error ->
                    logger.error("Failed to repost Telegram update {} to TikTok", update.updateId, error)
                    sendStatus(message.chatId, "Не удалось передать фото в TikTok: ${error.message}")
                }
            }
        }
    }

    private fun sendStatus(chatId: Long, text: String) {
        runCatching { client.execute(SendMessage(chatId.toString(), text.take(4096))) }
            .onFailure { logger.warn("Could not send Telegram status message", it) }
    }
}
