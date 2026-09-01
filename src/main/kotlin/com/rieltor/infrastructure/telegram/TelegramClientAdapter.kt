package com.rieltor.infrastructure.telegram

import com.rieltor.application.model.TelegramSourceState
import com.rieltor.application.port.TelegramMessageSource
import com.rieltor.domain.model.TelegramListing
import com.rieltor.domain.model.TelegramMonitoredTopic
import it.tdlight.Init
import it.tdlight.Log
import it.tdlight.Slf4JLogMessageHandler
import it.tdlight.client.*
import it.tdlight.jni.TdApi
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Telegram transport backed by TDLib. Emits monitored messages and knows nothing about TikTok or Google Drive. */
class TelegramClientAdapter(
    private val apiId: Int,
    private val apiHash: String,
    private val sessionDirectory: Path,
    private val monitoredTopics: Set<TelegramMonitoredTopic>,
    private val repostDelayMillis: Long = REPOST_DELAY_MILLIS,
) : TelegramMessageSource {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val messageChannel = Channel<TelegramListing>(Channel.BUFFERED)
    private val mutableState = MutableStateFlow<TelegramSourceState>(TelegramSourceState.Stopped)
    private val pendingMessageContents = ConcurrentHashMap<MessageKey, TdApi.MessageContent>()
    private val messageMapper = TelegramMessageMapper()
    private val diagnostics = TelegramDiagnostics(monitoredTopics)
    private val started = AtomicBoolean(false)
    private val startupMonitoringLogged = AtomicBoolean(false)
    private val albumCollector = MediaAlbumCollector<TdApi.Message>(
        scope = scope,
        settleDelayMillis = ALBUM_SETTLE_DELAY_MILLIS,
        maxItemCount = TELEGRAM_MAX_ALBUM_SIZE,
        itemId = { it.id },
        onReady = ::deliverMessages,
    )
    private var factory: SimpleTelegramClientFactory? = null
    private var client: SimpleTelegramClient? = null

    override val messages: Flow<TelegramListing> = messageChannel.receiveAsFlow()
    override val state: StateFlow<TelegramSourceState> = mutableState.asStateFlow()

    override fun start() {
        if (!started.compareAndSet(false, true)) return
        mutableState.value = TelegramSourceState.Starting
        try {
            Files.createDirectories(sessionDirectory.resolve("data"))
            Files.createDirectories(sessionDirectory.resolve("downloads"))

            Init.init()
            Log.setLogMessageHandler(1, Slf4JLogMessageHandler())
            val clientFactory = SimpleTelegramClientFactory()
            val tdSettings = TDLibSettings.create(APIToken(apiId, apiHash)).also {
                it.databaseDirectoryPath = sessionDirectory.resolve("data")
                it.downloadedFilesDirectoryPath = sessionDirectory.resolve("downloads")
            }
            val builder = clientFactory.builder(tdSettings)
            builder.addUpdateHandler(TdApi.UpdateAuthorizationState::class.java, ::onAuthorizationState)
            builder.addUpdateHandler(TdApi.UpdateNewMessage::class.java, ::onNewMessage)
            builder.addUpdateHandler(TdApi.UpdateMessageContent::class.java, ::onMessageContentUpdated)
            builder.addUpdateExceptionHandler { error ->
                logger.error("Telegram update handler failed", error)
            }
            builder.addDefaultExceptionHandler { error -> logger.error("Telegram client request failed", error) }

            factory = clientFactory
            client = builder.build(AuthenticationSupplier.qrCode())
            logger.info(
                "Telegram TDLib client started. session={}",
                sessionDirectory.toAbsolutePath(),
            )
        } catch (error: Throwable) {
            mutableState.value = TelegramSourceState.Failed(error.failureReason())
            throw error
        }
    }

    private fun onAuthorizationState(update: TdApi.UpdateAuthorizationState) {
        when (update.authorizationState) {
            is TdApi.AuthorizationStateReady -> {
                mutableState.value = TelegramSourceState.Ready
                logger.info("Telegram TDLib session is authorized and ready")
                if (startupMonitoringLogged.compareAndSet(false, true)) {
                    scope.launch {
                        delay(STARTUP_MONITORING_LOG_DELAY_MILLIS)
                        client?.let(diagnostics::logStartupSnapshot)
                    }
                }
            }
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> {
                mutableState.value = TelegramSourceState.AwaitingAuthorization
                logger.warn(
                    "Telegram session needs confirmation in an already authorized Telegram app. " +
                        "Use the short-lived QR/login link printed by TDLight; do not share it."
                )
            }
            is TdApi.AuthorizationStateWaitPhoneNumber,
            is TdApi.AuthorizationStateWaitCode,
            is TdApi.AuthorizationStateWaitPassword -> {
                mutableState.value = TelegramSourceState.AwaitingAuthorization
                logger.warn(
                    "Telegram session needs interactive authorization; use the QR/login link from an authorized device."
                )
            }
            is TdApi.AuthorizationStateLoggingOut -> {
                mutableState.value = TelegramSourceState.Starting
                logger.warn("Telegram TDLib session is logging out")
            }
            is TdApi.AuthorizationStateClosed -> {
                mutableState.value = TelegramSourceState.Stopped
                messageChannel.close()
                logger.warn("Telegram TDLib session is closed")
            }
            else -> logger.debug("Telegram authorization state: {}", update.authorizationState.javaClass.simpleName)
        }
    }

    private fun onNewMessage(update: TdApi.UpdateNewMessage) {
        val message = update.message
        if (!isMonitored(message)) return

        logger.info(
            "Telegram monitored message: chatId={}, messageThreadId={}, messageId={}, message={}",
            message.chatId,
            message.messageThreadId,
            message.id,
            message.summary(),
        )

        when (message.content) {
            is TdApi.MessagePhoto -> {
                pendingMessageContents[message.key()] = message.content
                if (message.mediaAlbumId == 0L) enqueue(listOf(message))
                else albumCollector.add(message.mediaAlbumId, message)
            }
            is TdApi.MessageText -> {
                pendingMessageContents[message.key()] = message.content
                enqueue(listOf(message))
            }
            else -> Unit
        }
    }

    private fun onMessageContentUpdated(update: TdApi.UpdateMessageContent) {
        val key = MessageKey(update.chatId, update.messageId)
        val wasPending = pendingMessageContents.computeIfPresent(key) { _, _ -> update.newContent } != null
        if (wasPending) {
            logger.info(
                "Updated pending Telegram message before repost. chatId={}, messageId={}",
                update.chatId,
                update.messageId,
            )
        }
    }

    private fun enqueue(messages: List<TdApi.Message>) {
        scope.launch {
            try {
                deliverMessages(messages)
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                logger.error(
                    "Telegram message delivery abandoned after retries. chatId={}, messageIds={}, reason={}",
                    messages.firstOrNull()?.chatId,
                    messages.map(TdApi.Message::id),
                    error.message,
                )
            } catch (error: Throwable) {
                logger.error("Could not convert or deliver Telegram message", error)
            }
        }
    }

    private suspend fun deliverMessages(messages: List<TdApi.Message>) {
        val messageKeys = messages.map { message -> message.key() }
        try {
            logger.info(
                "Telegram message scheduled for repost in {} minutes. chatId={}, messageIds={}",
                TimeUnit.MILLISECONDS.toMinutes(repostDelayMillis),
                messages.firstOrNull()?.chatId,
                messages.map(TdApi.Message::id),
            )
            delay(repostDelayMillis)
            logger.info(
                "Telegram repost delay elapsed; message is ready for independent processing. chatId={}, messageIds={}",
                messages.firstOrNull()?.chatId,
                messages.map(TdApi.Message::id),
            )

            repeat(DELIVERY_MAX_ATTEMPTS) { attempt ->
                try {
                    val telegramClient = client ?: return
                    val latestMessages = buildList {
                        for (original in messages) {
                            when (val refreshed = fetchLatestMessage(telegramClient, original)) {
                                is TelegramMessageRefresh.Found -> add(refreshed.message)
                                TelegramMessageRefresh.NotFound -> {
                                    logger.info(
                                        "Telegram message was deleted before repost; skipping it. " +
                                            "chatId={}, messageId={}",
                                        original.chatId,
                                        original.id,
                                    )
                                    return
                                }

                                TelegramMessageRefresh.Unavailable -> add(original.also { fallback ->
                                    pendingMessageContents[original.key()]?.let { latestContent ->
                                        fallback.content = latestContent
                                    }
                                })
                            }
                        }
                    }
                    val message = messageMapper.map(telegramClient, latestMessages) ?: return
                    var delivered = false
                    try {
                        messageChannel.send(message)
                        delivered = true
                    } finally {
                        if (!delivered) message.closePhotos()
                    }
                    return
                } catch (error: CancellationException) {
                    throw error
                } catch (error: IOException) {
                    if (attempt + 1 >= DELIVERY_MAX_ATTEMPTS) throw error
                    val retryDelay = DELIVERY_RETRY_DELAY_MILLIS * (attempt + 1)
                    logger.warn(
                        "Temporary Telegram media error; delivery will be retried. " +
                            "chatId={}, messageIds={}, attempt={}/{}, retryInSeconds={}, reason={}",
                        messages.firstOrNull()?.chatId,
                        messages.map(TdApi.Message::id),
                        attempt + 1,
                        DELIVERY_MAX_ATTEMPTS,
                        TimeUnit.MILLISECONDS.toSeconds(retryDelay),
                        error.message,
                    )
                    delay(retryDelay)
                }
            }
        } finally {
            messageKeys.forEach(pendingMessageContents::remove)
        }
    }

    private suspend fun fetchLatestMessage(
        telegramClient: SimpleTelegramClient,
        original: TdApi.Message,
    ): TelegramMessageRefresh {
        repeat(GET_MESSAGE_MAX_ATTEMPTS) { attempt ->
            try {
                return TelegramMessageRefresh.Found(
                    telegramClient.send(TdApi.GetMessage(original.chatId, original.id))
                        .get(GET_MESSAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val refreshFailure = error.telegramRefreshFailure()
                when (refreshFailure) {
                    TelegramRefreshFailure.MESSAGE_NOT_FOUND -> return TelegramMessageRefresh.NotFound

                    else -> Unit
                }
                if (attempt + 1 < GET_MESSAGE_MAX_ATTEMPTS) {
                    delay(GET_MESSAGE_RETRY_DELAY_MILLIS * (attempt + 1))
                } else {
                    if (refreshFailure == TelegramRefreshFailure.TIMEOUT) {
                        logger.warn(
                            "Could not refresh Telegram message before repost; using content received via updates. " +
                                "chatId={}, messageId={}, reason=timeout",
                            original.chatId,
                            original.id,
                        )
                    } else {
                        logger.warn(
                            "Could not refresh Telegram message before repost; using content received via updates. " +
                                "chatId={}, messageId={}",
                            original.chatId,
                            original.id,
                            error,
                        )
                    }
                }
            }
        }
        return TelegramMessageRefresh.Unavailable
    }

    private fun isMonitored(message: TdApi.Message): Boolean =
        monitoredTopics.any { monitored -> monitored.matches(message.chatId, message.messageThreadId) }

    override fun close() {
        albumCollector.close()
        messageChannel.close()
        scope.cancel()
        pendingMessageContents.clear()
        runCatching { client?.closeAndWait() }
            .onFailure { logger.warn("Could not close Telegram client cleanly", it) }
        runCatching { factory?.close() }
            .onFailure { logger.warn("Could not close Telegram client factory cleanly", it) }
        client = null
        factory = null
        mutableState.value = TelegramSourceState.Stopped
    }

    private fun TelegramListing.closePhotos() {
        photos.forEach { photo -> runCatching { photo.content.close() } }
    }

    private fun Throwable.failureReason(): String = message?.takeIf(String::isNotBlank)
        ?: javaClass.simpleName

    private fun TdApi.Message.key(): MessageKey = MessageKey(chatId, id)

    private data class MessageKey(val chatId: Long, val messageId: Long)

    private companion object {
        const val REPOST_DELAY_MILLIS = 20 * 60 * 1_000L
        const val ALBUM_SETTLE_DELAY_MILLIS = 2_000L
        const val TELEGRAM_MAX_ALBUM_SIZE = 10
        const val STARTUP_MONITORING_LOG_DELAY_MILLIS = 500L
        const val GET_MESSAGE_TIMEOUT_SECONDS = 30L
        const val GET_MESSAGE_MAX_ATTEMPTS = 2
        const val GET_MESSAGE_RETRY_DELAY_MILLIS = 1_000L
        const val DELIVERY_MAX_ATTEMPTS = 3
        const val DELIVERY_RETRY_DELAY_MILLIS = 60_000L
    }
}

private sealed interface TelegramMessageRefresh {
    data class Found(val message: TdApi.Message) : TelegramMessageRefresh
    data object NotFound : TelegramMessageRefresh
    data object Unavailable : TelegramMessageRefresh
}

internal enum class TelegramRefreshFailure {
    MESSAGE_NOT_FOUND,
    TIMEOUT,
}

internal fun Throwable.telegramRefreshFailure(): TelegramRefreshFailure? {
    var current: Throwable? = this
    val visited = HashSet<Throwable>()
    while (current != null && visited.add(current)) {
        when (current) {
            is TelegramError -> if (current.errorCode == 404) {
                return TelegramRefreshFailure.MESSAGE_NOT_FOUND
            }

            is java.util.concurrent.TimeoutException -> return TelegramRefreshFailure.TIMEOUT
        }
        current = current.cause
    }
    return null
}
