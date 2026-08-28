package com.rieltor.infrastructure.telegram

import com.rieltor.application.PhotoRepostService
import com.rieltor.domain.model.RepostResult
import com.rieltor.domain.model.TelegramPhoto
import com.rieltor.domain.model.TelegramPhotoMessage
import com.rieltor.infrastructure.tiktok.TikTokAuthException
import it.tdlight.Init
import it.tdlight.Log
import it.tdlight.Slf4JLogMessageHandler
import it.tdlight.client.APIToken
import it.tdlight.client.AuthenticationSupplier
import it.tdlight.client.SimpleTelegramClient
import it.tdlight.client.SimpleTelegramClientFactory
import it.tdlight.client.TDLibSettings
import it.tdlight.jni.TdApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Telegram user client backed by the copied TDLib session. No Bot API is used. */
class TelegramClientAdapter(
    private val apiId: Int,
    private val apiHash: String,
    private val sessionDirectory: Path,
    private val monitoredChatIds: Set<Long>,
    private val monitoredTopicIds: Set<Long>,
    private val repostService: PhotoRepostService,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val albumCollector = MediaAlbumCollector<TdApi.Message>(
        scope = scope,
        settleDelayMillis = ALBUM_SETTLE_DELAY_MILLIS,
        maxItemCount = TELEGRAM_MAX_ALBUM_SIZE,
        itemId = { it.id },
        onReady = ::processPhotoMessages,
    )
    private var factory: SimpleTelegramClientFactory? = null
    private var client: SimpleTelegramClient? = null
    private val startupMonitoringLogged = AtomicBoolean(false)

    fun start() {
        Files.createDirectories(sessionDirectory.resolve("data"))
        Files.createDirectories(sessionDirectory.resolve("downloads"))

        Init.init()
        // TDLib emits a high-volume trace stream at levels 2–5. Keep only fatal native errors.
        Log.setLogMessageHandler(1, Slf4JLogMessageHandler())
        val clientFactory = SimpleTelegramClientFactory()
        val tdSettings = TDLibSettings.create(APIToken(apiId, apiHash)).also {
            it.databaseDirectoryPath = sessionDirectory.resolve("data")
            it.downloadedFilesDirectoryPath = sessionDirectory.resolve("downloads")
        }
        val builder = clientFactory.builder(tdSettings)
        builder.addUpdateHandler(TdApi.UpdateAuthorizationState::class.java, ::onAuthorizationState)
        builder.addUpdateHandler(TdApi.UpdateNewMessage::class.java, ::onNewMessage)
        builder.addUpdateExceptionHandler { error -> logger.error("Telegram update handler failed", error) }
        builder.addDefaultExceptionHandler { error -> logger.error("Telegram client request failed", error) }

        factory = clientFactory
        client = builder.build(AuthenticationSupplier.qrCode())
        logger.info(
            "Telegram TDLib client started. session={}, monitoredChatIds={}, monitoredTopicIds={}",
            sessionDirectory.toAbsolutePath(),
            monitoredChatIds,
            monitoredTopicIds,
        )
    }

    private fun onAuthorizationState(update: TdApi.UpdateAuthorizationState) {
        when (update.authorizationState) {
            is TdApi.AuthorizationStateReady -> {
                logger.info("Telegram TDLib session is authorized and ready")
                if (startupMonitoringLogged.compareAndSet(false, true)) {
                    scope.launch {
                        delay(STARTUP_MONITORING_LOG_DELAY_MILLIS)
                        logMonitoredTopics()
                    }
                }
            }
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> logger.warn(
                "Telegram session needs confirmation in an already authorized Telegram app. " +
                    "Use the short-lived QR/login link printed by TDLight; do not share it."
            )
            is TdApi.AuthorizationStateWaitPhoneNumber,
            is TdApi.AuthorizationStateWaitCode,
            is TdApi.AuthorizationStateWaitPassword -> logger.warn(
                "Telegram session needs interactive authorization; use the QR/login link from an authorized device."
            )
            is TdApi.AuthorizationStateLoggingOut -> logger.warn("Telegram TDLib session is logging out")
            is TdApi.AuthorizationStateClosed -> logger.warn("Telegram TDLib session is closed")
            else -> logger.debug("Telegram authorization state: {}", update.authorizationState.javaClass.simpleName)
        }
    }

    private fun onNewMessage(update: TdApi.UpdateNewMessage) {
        val message = update.message
        if (message.content !is TdApi.MessagePhoto) return
        if (!isMonitored(message)) return

        if (message.mediaAlbumId == 0L) {
            scope.launch { processPhotoMessages(listOf(message)) }
        } else {
            albumCollector.add(message.mediaAlbumId, message)
        }
    }

    private fun isMonitored(message: TdApi.Message): Boolean {
        if (message.chatId !in monitoredChatIds) return false
        return monitoredTopicIds.isEmpty() || message.messageThreadId in monitoredTopicIds
    }

    private fun logMonitoredTopics() {
        val telegramClient = client ?: return
        monitoredChatIds.forEach { chatId ->
            runCatching {
                telegramClient.send(
                    TdApi.GetForumTopics(chatId, "", 0, 0, 0, FORUM_TOPIC_LOG_LIMIT)
                ).get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }.onSuccess { forumTopics ->
                val topics = forumTopics.topics
                    .filter { monitoredTopicIds.isEmpty() || it.info.messageThreadId in monitoredTopicIds }
                logger.info(
                    "Telegram monitoring check: chatId={}, configuredTopicIds={}, foundTopics={}, totalTopics={}",
                    chatId,
                    monitoredTopicIds.takeIf { it.isNotEmpty() } ?: "all",
                    topics.size,
                    forumTopics.totalCount,
                )
                topics.forEach { topic ->
                    val lastMessage = topic.lastMessage
                    logger.info(
                        "Telegram monitored topic: chatId={}, topicId={}, name='{}', lastMessageId={}, lastMessage={}",
                        chatId,
                        topic.info.messageThreadId,
                        topic.info.name,
                        lastMessage?.id,
                        lastMessage?.let(::messageSummary) ?: "no messages",
                    )
                    logger.info("---------")
                }
                if (monitoredTopicIds.isNotEmpty()) {
                    val foundTopicIds = topics.map { it.info.messageThreadId }.toSet()
                    val missingTopicIds = monitoredTopicIds - foundTopicIds
                    if (missingTopicIds.isNotEmpty()) {
                        logger.warn("Configured Telegram topic IDs were not found in chat {}: {}", chatId, missingTopicIds)
                    }
                }
                if (forumTopics.totalCount > forumTopics.topics.size) {
                    logger.warn(
                        "Telegram chat {} has more than {} topics; startup log shows only the newest topics",
                        chatId,
                        FORUM_TOPIC_LOG_LIMIT,
                    )
                }
            }.onFailure { error ->
                logger.warn("Could not read Telegram forum topics for monitored chat {}", chatId, error)
            }
        }
    }

    private fun messageSummary(message: TdApi.Message): String = when (val content = message.content) {
        is TdApi.MessageText -> content.text.text.take(LOG_MESSAGE_TEXT_LIMIT).replace('\n', ' ')
        is TdApi.MessagePhoto -> "photo: ${content.caption.text.take(LOG_MESSAGE_TEXT_LIMIT)}".replace('\n', ' ')
        else -> content.javaClass.simpleName.removePrefix("Message")
    }

    private suspend fun processPhotoMessages(messages: List<TdApi.Message>) {
        val orderedMessages = messages.sortedBy { it.id }
        val firstMessage = orderedMessages.firstOrNull() ?: return
        val telegramClient = client ?: return
        val caption = orderedMessages.asSequence()
            .mapNotNull { (it.content as? TdApi.MessagePhoto)?.caption?.text?.trim() }
            .firstOrNull { it.isNotBlank() }

        runCatching {
            val photos = mutableListOf<TelegramPhoto>()
            try {
                orderedMessages.forEach { message ->
                    val photoContent = message.content as? TdApi.MessagePhoto
                        ?: error("Telegram media album contains a non-photo message")
                    val largestPhoto = photoContent.photo.sizes.maxByOrNull { size ->
                        size.photo.expectedSize.takeIf { it > 0 } ?: (size.width.toLong() * size.height.toLong())
                    } ?: error("Telegram photo has no downloadable sizes")
                    val downloaded = downloadPhoto(telegramClient, largestPhoto.photo.id)
                    val localPath = downloaded.local?.path
                        ?.takeIf { it.isNotBlank() }
                        ?.let(Path::of)
                        ?: error("TDLib did not return a local photo path")
                    photos += TelegramPhoto(
                        fileName = localPath.fileName.toString(),
                        content = Files.newInputStream(localPath),
                    )
                }
                val sourceId = firstMessage.mediaAlbumId.takeIf { it != 0L } ?: firstMessage.id
                val updateId = java.lang.Long.rotateLeft(firstMessage.chatId, 17) xor sourceId
                repostService.handle(
                    TelegramPhotoMessage(
                        updateId = updateId,
                        chatId = firstMessage.chatId,
                        caption = caption,
                        photos = photos.toList(),
                    )
                )
            } finally {
                photos.forEach { photo -> runCatching { photo.content.close() } }
            }
        }.onSuccess { result ->
            when (result) {
                is RepostResult.Published -> logger.info(
                    "Telegram photo post submitted to TikTok. publishId={}, privacy={}, photoCount={}",
                    result.receipt.publishId,
                    result.receipt.privacyLevel,
                    orderedMessages.size,
                )
                RepostResult.Duplicate -> logger.info("Telegram photo post was already processed")
                RepostResult.IgnoredSource -> Unit
            }
        }.onFailure { error ->
            if (error is TikTokAuthException) {
                logger.warn("TikTok rejected Telegram photo post {}: {}", firstMessage.id, error.message)
            } else {
                logger.error("Failed to repost Telegram photo post {} to TikTok", firstMessage.id, error)
            }
        }
    }

    private suspend fun downloadPhoto(telegramClient: SimpleTelegramClient, fileId: Int): TdApi.File {
        var lastError: Throwable? = null
        repeat(DOWNLOAD_MAX_ATTEMPTS) { attempt ->
            try {
                return telegramClient.send(TdApi.DownloadFile(fileId, 1, 0, 0, true))
                    .get(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (error: java.util.concurrent.TimeoutException) {
                lastError = error
                if (attempt + 1 < DOWNLOAD_MAX_ATTEMPTS) delay(DOWNLOAD_RETRY_DELAY_MILLIS * (attempt + 1))
            }
        }
        throw IOException(
            "Telegram photo download timed out after $DOWNLOAD_MAX_ATTEMPTS attempts: " +
                (lastError?.message ?: "timeout"),
            lastError,
        )
    }

    override fun close() {
        albumCollector.close()
        scope.cancel()
        runCatching { client?.closeAndWait() }
            .onFailure { logger.warn("Could not close Telegram client cleanly", it) }
        factory?.close()
        client = null
        factory = null
    }

    companion object {
        private const val ALBUM_SETTLE_DELAY_MILLIS = 2_000L
        private const val TELEGRAM_MAX_ALBUM_SIZE = 10
        private const val FORUM_TOPIC_LOG_LIMIT = 100
        private const val REQUEST_TIMEOUT_SECONDS = 30L
        private const val LOG_MESSAGE_TEXT_LIMIT = 160
        private const val STARTUP_MONITORING_LOG_DELAY_MILLIS = 500L
        private const val DOWNLOAD_TIMEOUT_SECONDS = 120L
        private const val DOWNLOAD_MAX_ATTEMPTS = 3
        private const val DOWNLOAD_RETRY_DELAY_MILLIS = 1_000L
    }
}
