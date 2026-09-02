package com.rieltor.infrastructure.telegram

import com.rieltor.application.port.TelegramBotIncomingMessage
import com.rieltor.application.port.TelegramBotReplySender
import com.rieltor.application.usecase.ReplyWithFormattedListingUseCase
import com.rieltor.domain.model.TelegramPhoto
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.telegram.telegrambots.meta.api.objects.MessageEntity
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.media.InputMedia
import org.telegram.telegrambots.meta.api.objects.media.InputMediaPhoto
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.util.concurrent.atomic.AtomicBoolean

/** Bot API entrypoint. Unlike monitored TDLib chats, bot messages are processed immediately. */
class TelegramListingBot(
    private val botToken: String,
    private val replyUseCase: ReplyWithFormattedListingUseCase,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private var application: TelegramBotsLongPollingApplication? = null

    fun start() {
        if (botToken.isBlank()) {
            logger.warn("Telegram listing bot is disabled: bot token is not configured")
            return
        }
        if (!started.compareAndSet(false, true)) {
            return
        }

        val longPolling = TelegramBotsLongPollingApplication()
        try {
            longPolling.registerBot(botToken, LongPollingUpdateConsumer(::consumeUpdates))
            application = longPolling
            logger.info("Telegram listing bot started. processingMode=immediate")
        } catch (error: Throwable) {
            logger.error("Telegram listing bot long polling failed to start", error)
            started.set(false)
            runCatching { longPolling.close() }
            throw error
        }
    }

    private fun consumeUpdates(updates: List<Update>) {
        updates.mapNotNull { update -> update.toIncomingMessageOrLog() }.forEach { message ->
            scope.launch {
                try {
                    replyUseCase.execute(message)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    logger.error(
                        "Could not process Telegram listing bot message. chatId={}, messageId={}",
                        message.chatId,
                        message.messageId,
                        error,
                    )
                }
            }
        }
    }

    override fun close() {
        scope.cancel()
        runCatching { application?.close() }
            .onFailure { logger.warn("Could not close Telegram listing bot cleanly", it) }
        application = null
        started.set(false)
    }

    private fun Update.toIncomingMessageOrLog(): TelegramBotIncomingMessage? {
        val incoming = message
        if (incoming == null) {
            logger.debug(
                "Telegram listing bot update ignored: it is not a new message. updateId={}, updateType={}",
                updateId,
                updateType(),
            )
            return null
        }
        val sourceText = incoming.text ?: incoming.caption
        if (sourceText == null) {
            logger.debug(
                "Telegram listing bot message ignored: no text or caption. updateId={}, chatId={}, " +
                    "messageId={}, hasPhoto={}, hasDocument={}",
                updateId,
                incoming.chatId,
                incoming.messageId,
                incoming.hasPhoto(),
                incoming.hasDocument(),
            )
            return null
        }
        val entities = if (incoming.text != null) incoming.entities else incoming.captionEntities
        val embeddedLinks = entities.orEmpty()
            .asSequence()
            .mapNotNull(MessageEntity::getUrl)
            .filter(String::isNotBlank)
            .distinct()
            .toList()
        logger.info(
            "Telegram listing bot message received. chatId={}, messageId={}, senderUserId={}, forwarded={}, textLength={}",
            incoming.chatId,
            incoming.messageId,
            incoming.from?.id,
            incoming.forwardOrigin != null || incoming.forwardFrom != null || incoming.forwardFromChat != null,
            sourceText.length,
        )
        return TelegramBotIncomingMessage(
            chatId = incoming.chatId,
            messageId = incoming.messageId,
            messageThreadId = incoming.messageThreadId,
            text = sourceText.withEmbeddedLinks(embeddedLinks),
        )
    }

    private fun String.withEmbeddedLinks(links: List<String>): String {
        val missingLinks = links.filterNot(::contains)
        return if (missingLinks.isEmpty()) this else (listOf(this) + missingLinks).joinToString("\n")
    }

    private fun Update.updateType(): String = when {
        editedMessage != null -> "editedMessage"
        channelPost != null -> "channelPost"
        editedChannelPost != null -> "editedChannelPost"
        callbackQuery != null -> "callbackQuery"
        inlineQuery != null -> "inlineQuery"
        else -> "unsupported"
    }
}

class TelegramBotApiReplySender(
    private val telegramClient: TelegramClient,
) : TelegramBotReplySender {
    constructor(botToken: String) : this(OkHttpTelegramClient(botToken))

    override suspend fun sendText(
        chatId: Long,
        messageThreadId: Int?,
        replyToMessageId: Int?,
        text: String,
    ) = withContext(Dispatchers.IO) {
        val request = SendMessage(chatId.toString(), text).apply {
            setMessageThreadId(messageThreadId)
            setReplyToMessageId(replyToMessageId)
            setAllowSendingWithoutReply(true)
            disableWebPagePreview()
        }
        telegramClient.execute(request)
        Unit
    }

    override suspend fun sendPhotos(
        chatId: Long,
        messageThreadId: Int?,
        photos: List<TelegramPhoto>,
    ) = withContext(Dispatchers.IO) {
        require(photos.size in 1..TELEGRAM_MEDIA_GROUP_LIMIT) {
            "Telegram photo batch must contain from 1 to $TELEGRAM_MEDIA_GROUP_LIMIT photos"
        }
        if (photos.size == 1) {
            val photo = photos.single()
            val request = SendPhoto(
                chatId.toString(),
                InputFile(photo.content, photo.safeUploadName(0)),
            ).apply {
                setMessageThreadId(messageThreadId)
            }
            telegramClient.execute(request)
        } else {
            val media: List<InputMedia> = photos.mapIndexed { index, photo ->
                InputMediaPhoto(photo.content, photo.safeUploadName(index))
            }
            val request = SendMediaGroup(chatId.toString(), media).apply {
                setMessageThreadId(messageThreadId)
            }
            telegramClient.execute(request)
        }
        Unit
    }

    private fun TelegramPhoto.safeUploadName(index: Int): String {
        val cleanName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)
        return "photo-${index + 1}-${cleanName.ifBlank { "image.jpg" }}"
    }

    private companion object {
        const val TELEGRAM_MEDIA_GROUP_LIMIT = 10
    }
}
