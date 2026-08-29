package com.rieltor.infrastructure.telegram

import com.rieltor.application.PhotoRepostService
import com.rieltor.domain.model.RepostResult
import com.rieltor.domain.model.TelegramPhoto
import com.rieltor.domain.model.TelegramPhotoMessage
import com.rieltor.domain.model.TelegramMonitoredTopic
import com.rieltor.domain.port.ExternalPhotoSource
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
    private val monitoredTopics: Set<TelegramMonitoredTopic>,
    private val repostService: PhotoRepostService,
    private val externalPhotoSource: ExternalPhotoSource,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val albumCollector = MediaAlbumCollector<TdApi.Message>(
        scope = scope,
        settleDelayMillis = ALBUM_SETTLE_DELAY_MILLIS,
        maxItemCount = TELEGRAM_MAX_ALBUM_SIZE,
        itemId = { it.id },
        onReady = ::processMessages,
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
            "Telegram TDLib client started. session={}, monitoredTopics={}",
            sessionDirectory.toAbsolutePath(),
            monitoredTopics,
        )
    }

    private fun onAuthorizationState(update: TdApi.UpdateAuthorizationState) {
        when (update.authorizationState) {
            is TdApi.AuthorizationStateReady -> {
                logger.info("Telegram TDLib session is authorized and ready")
                if (startupMonitoringLogged.compareAndSet(false, true)) {
                    scope.launch {
                        delay(STARTUP_MONITORING_LOG_DELAY_MILLIS)
                        logAuthorizedAccount()
                        logAvailableTelegramChats()
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
        if (!isMonitored(message)) return

        logger.info(
            "Telegram monitored message: chatId={}, messageThreadId={}, messageId={}, message={}",
            message.chatId,
            message.messageThreadId,
            message.id,
            messageSummary(message),
        )

        when (val content = message.content) {
            is TdApi.MessagePhoto -> {
                if (message.mediaAlbumId == 0L) {
                    scope.launch { processMessages(listOf(message)) }
                } else {
                    albumCollector.add(message.mediaAlbumId, message)
                }
            }
            is TdApi.MessageText -> {
                if (externalPhotoSource.containsLink(content.text.textWithEmbeddedLinks())) {
                    scope.launch { processMessages(listOf(message)) }
                }
            }
            else -> Unit
        }
    }

    private fun isMonitored(message: TdApi.Message): Boolean {
        return monitoredTopics.any { monitored -> monitored.matches(message.chatId, message.messageThreadId) }
    }

    private fun logAuthorizedAccount() {
        val telegramClient = client ?: return
        runCatching {
            telegramClient.send(TdApi.GetMe()).get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }.onSuccess { user ->
            logger.info(
                "Telegram authorized account: userId={}, name='{} {}', username={}",
                user.id,
                user.firstName,
                user.lastName,
                user.usernames?.activeUsernames?.firstOrNull()?.let { "@$it" } ?: "none",
            )
        }.onFailure { error ->
            logger.warn("Could not read the authorized Telegram account", error)
        }
    }

    private fun logAvailableTelegramChats() {
        val telegramClient = client ?: return
        val chatLists = listOf(TdApi.ChatListMain(), TdApi.ChatListArchive())
        chatLists.forEach { chatList ->
            for (batch in 0 until CHAT_LOAD_BATCH_COUNT) {
                val loaded = runCatching {
                    telegramClient.send(TdApi.LoadChats(chatList, CHAT_LOAD_BATCH_SIZE))
                        .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }.isSuccess
                if (!loaded) break
            }
        }

        val chatIds = linkedSetOf<Long>()
        chatLists.forEach { chatList ->
            runCatching {
                telegramClient.send(TdApi.GetChats(chatList, AVAILABLE_CHAT_LOG_LIMIT))
                    .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }.getOrNull()?.chatIds?.forEach(chatIds::add)
        }

        val supergroupChats = chatIds.mapNotNull { chatId ->
            runCatching { getChat(telegramClient, chatId) }.getOrNull()
        }.mapNotNull { chat ->
            val type = chat.type as? TdApi.ChatTypeSupergroup ?: return@mapNotNull null
            chat to type
        }

        logger.info("Telegram available supergroup chats: count={}", supergroupChats.size)
        supergroupChats.forEach { (chat, type) ->
            val supergroup = runCatching {
                telegramClient.send(TdApi.GetSupergroup(type.supergroupId))
                    .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }.getOrNull()
            val membership = supergroup?.status?.javaClass?.simpleName
                ?.removePrefix("ChatMemberStatus") ?: "unknown"
            val kind = when {
                supergroup?.isForum == true -> "forum"
                type.isChannel -> "channel"
                else -> "group"
            }
            logger.info(
                "Telegram available chat: chatId={}, supergroupId={}, type={}, title='{}', membership={}",
                chat.id,
                type.supergroupId,
                kind,
                chat.title,
                membership,
            )
        }
    }

    private fun logMonitoredTopics() {
        val telegramClient = client ?: return
        monitoredTopics
            .filter { it.messageThreadId == null }
            .forEach { monitored ->
                logger.info("Telegram monitoring check: all messages in chatId={} are monitored", monitored.chatId)
            }

        monitoredTopics
            .mapTo(linkedSetOf()) { it.chatId }
            .forEach { chatId ->
                runCatching {
                    ensureChatLoaded(telegramClient, chatId)
                    val chat = getChat(telegramClient, chatId)
                    val chatType = chat.type as? TdApi.ChatTypeSupergroup
                        ?: return@runCatching null
                    val supergroup = telegramClient.send(TdApi.GetSupergroup(chatType.supergroupId))
                        .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    if (!supergroup.isForum) return@runCatching null

                    readAllForumTopics(telegramClient, chatId)
                }.onSuccess { forumTopics ->
                    if (forumTopics == null) return@onSuccess
                    val selectedTopics = forumTopics.topics.filter { topic ->
                        isTopicMonitored(chatId, topic.info.messageThreadId)
                    }
                    logger.info(
                        "Telegram monitoring check: chatId={}, configuredTopics={}, foundTopics={}, totalTopics={}",
                        chatId,
                        monitoredTopics.filter { it.chatId == chatId }.map { it.messageThreadId },
                        selectedTopics.size,
                        forumTopics.totalCount,
                    )
                    forumTopics.topics.forEach { topic ->
                        val lastMessage = topic.lastMessage
                        logger.info(
                            "Telegram forum topic: chatId={}, messageThreadId={}, name='{}', monitored={}, " +
                                "lastMessageId={}, lastMessage={}",
                            chatId,
                            topic.info.messageThreadId,
                            topic.info.name,
                            isTopicMonitored(chatId, topic.info.messageThreadId),
                            lastMessage?.id,
                            lastMessage?.let(::messageSummary) ?: "no messages",
                        )
                    }
                    val foundTopicIds = forumTopics.topics.map { it.info.messageThreadId }.toSet()
                    val missingTopicIds = monitoredTopics
                        .asSequence()
                        .filter {
                            it.chatId == chatId &&
                                it.messageThreadId != null &&
                                it.messageThreadId !in foundTopicIds
                        }
                        .map { it.messageThreadId }
                        .toList()
                    if (missingTopicIds.isNotEmpty()) {
                        logger.warn(
                            "Configured Telegram topic IDs were not found in chat {}: {}",
                            chatId,
                            missingTopicIds,
                        )
                    }
                }.onFailure { error ->
                    logger.warn(
                        "Could not read Telegram forum topics for monitored chat {}. " +
                            "Verify that this TDLib account is a member of the chat and that the parent chat ID is correct.",
                        chatId,
                        error,
                    )
                }
            }
    }

    private fun readAllForumTopics(
        telegramClient: SimpleTelegramClient,
        chatId: Long,
    ): ForumTopicSnapshot {
        val topicsById = linkedMapOf<Long, TdApi.ForumTopic>()
        val seenOffsets = mutableSetOf<ForumTopicOffset>()
        var offset = ForumTopicOffset(0, 0, 0)
        var totalCount = 0

        while (seenOffsets.add(offset)) {
            val page = telegramClient.send(
                TdApi.GetForumTopics(
                    chatId,
                    "",
                    offset.date,
                    offset.messageId,
                    offset.messageThreadId,
                    FORUM_TOPIC_PAGE_SIZE,
                )
            ).get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            totalCount = maxOf(totalCount, page.totalCount)
            page.topics.forEach { topic -> topicsById[topic.info.messageThreadId] = topic }

            if (page.topics.isEmpty() || topicsById.size >= totalCount) break
            offset = ForumTopicOffset(
                page.nextOffsetDate,
                page.nextOffsetMessageId,
                page.nextOffsetMessageThreadId,
            )
        }

        return ForumTopicSnapshot(totalCount, topicsById.values.toList())
    }

    private fun isTopicMonitored(chatId: Long, messageThreadId: Long): Boolean =
        monitoredTopics.any { monitored -> monitored.matches(chatId, messageThreadId) }

    private fun ensureChatLoaded(telegramClient: SimpleTelegramClient, chatId: Long) {
        if (runCatching { getChat(telegramClient, chatId) }.isSuccess) return

        val supergroupId = telegramChatIdToSupergroupId(chatId)
        if (supergroupId != null) {
            val createdChat = runCatching {
                telegramClient.send(TdApi.CreateSupergroupChat(supergroupId, true))
                    .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }.getOrNull()
            if (createdChat?.id == chatId) {
                logger.info(
                    "Telegram supergroup chat {} was loaded directly from supergroup ID {}",
                    chatId,
                    supergroupId,
                )
                return
            }
        }

        logger.info("Telegram chat {} is not in the local TDLib database; loading cloud chat lists", chatId)
        val chatLists = listOf(TdApi.ChatListMain(), TdApi.ChatListArchive())
        for (chatList in chatLists) {
            for (batch in 0 until CHAT_LOAD_BATCH_COUNT) {
                val loaded = runCatching {
                    telegramClient.send(TdApi.LoadChats(chatList, CHAT_LOAD_BATCH_SIZE))
                        .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }.isSuccess

                if (runCatching { getChat(telegramClient, chatId) }.isSuccess) return
                // TDLib reports an error when every chat in this list has already been loaded.
                if (!loaded) break
            }
        }

        runCatching { getChat(telegramClient, chatId) }.getOrElse { error ->
            logAvailableSupergroupChats(telegramClient)
            throw error
        }
    }

    private fun getChat(telegramClient: SimpleTelegramClient, chatId: Long): TdApi.Chat =
        telegramClient.send(TdApi.GetChat(chatId))
            .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private fun telegramChatIdToSupergroupId(chatId: Long): Long? {
        if (chatId >= TELEGRAM_SUPERGROUP_CHAT_ID_OFFSET) return null
        return -chatId + TELEGRAM_SUPERGROUP_CHAT_ID_OFFSET
    }

    private fun logAvailableSupergroupChats(telegramClient: SimpleTelegramClient) {
        val chatIds = linkedSetOf<Long>()
        listOf(TdApi.ChatListMain(), TdApi.ChatListArchive()).forEach { chatList ->
            runCatching {
                telegramClient.send(TdApi.GetChats(chatList, AVAILABLE_CHAT_LOG_LIMIT))
                    .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }.getOrNull()?.chatIds?.forEach(chatIds::add)
        }

        val supergroups = chatIds.mapNotNull { availableChatId ->
            runCatching { getChat(telegramClient, availableChatId) }.getOrNull()
        }.filter { it.type is TdApi.ChatTypeSupergroup }

        if (supergroups.isEmpty()) {
            logger.warn("TDLib account has no available supergroup chats in its main or archived chat lists")
            return
        }
        supergroups.forEach { chat ->
            val type = chat.type as TdApi.ChatTypeSupergroup
            logger.info(
                "Telegram available supergroup: chatId={}, supergroupId={}, title='{}', isChannel={}",
                chat.id,
                type.supergroupId,
                chat.title,
                type.isChannel,
            )
        }
    }

    private fun messageSummary(message: TdApi.Message): String = when (val content = message.content) {
        is TdApi.MessageText -> content.text.text.take(LOG_MESSAGE_TEXT_LIMIT).replace('\n', ' ')
        is TdApi.MessagePhoto -> "photo: ${content.caption.text.take(LOG_MESSAGE_TEXT_LIMIT)}".replace('\n', ' ')
        else -> content.javaClass.simpleName.removePrefix("Message")
    }

    private suspend fun processMessages(messages: List<TdApi.Message>) {
        val orderedMessages = messages.sortedBy { it.id }
        val firstMessage = orderedMessages.firstOrNull() ?: return
        val telegramClient = client ?: return
        val caption = orderedMessages.asSequence()
            .mapNotNull { message ->
                when (val content = message.content) {
                    is TdApi.MessagePhoto -> content.caption.textWithEmbeddedLinks().trim()
                    is TdApi.MessageText -> content.text.textWithEmbeddedLinks().trim()
                    else -> null
                }
            }
            .firstOrNull { it.isNotBlank() }

        runCatching {
            val photos = mutableListOf<TelegramPhoto>()
            try {
                orderedMessages.forEach { message ->
                    val photoContent = message.content as? TdApi.MessagePhoto ?: return@forEach
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
                        messageThreadId = firstMessage.messageThreadId,
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
                    "Telegram/Google Drive photo post submitted to TikTok. publishId={}, privacy={}",
                    result.receipt.publishId,
                    result.receipt.privacyLevel,
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

    private fun TdApi.FormattedText.textWithEmbeddedLinks(): String {
        val embeddedLinks = entities.asSequence()
            .mapNotNull { (it.type as? TdApi.TextEntityTypeTextUrl)?.url }
            .filter { it.isNotBlank() && !text.contains(it) }
            .distinct()
            .toList()
        return if (embeddedLinks.isEmpty()) text else (listOf(text) + embeddedLinks).joinToString("\n")
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
        private const val FORUM_TOPIC_PAGE_SIZE = 100
        private const val CHAT_LOAD_BATCH_SIZE = 100
        private const val CHAT_LOAD_BATCH_COUNT = 20
        private const val AVAILABLE_CHAT_LOG_LIMIT = 200
        private const val TELEGRAM_SUPERGROUP_CHAT_ID_OFFSET = -1_000_000_000_000L
        private const val REQUEST_TIMEOUT_SECONDS = 30L
        private const val LOG_MESSAGE_TEXT_LIMIT = 160
        private const val STARTUP_MONITORING_LOG_DELAY_MILLIS = 500L
        private const val DOWNLOAD_TIMEOUT_SECONDS = 120L
        private const val DOWNLOAD_MAX_ATTEMPTS = 3
        private const val DOWNLOAD_RETRY_DELAY_MILLIS = 1_000L
    }

    private data class ForumTopicOffset(
        val date: Int,
        val messageId: Long,
        val messageThreadId: Long,
    )

    private data class ForumTopicSnapshot(
        val totalCount: Int,
        val topics: List<TdApi.ForumTopic>,
    )
}
