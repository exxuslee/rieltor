package com.rieltor.infrastructure.telegram

import com.rieltor.application.model.TelegramSourceState
import com.rieltor.application.port.TelegramInboxSource
import com.rieltor.domain.model.SourceMessage
import com.rieltor.domain.model.SourcePhoto
import com.rieltor.domain.model.SourceRefresh
import com.rieltor.domain.model.TelegramMonitoredTopic
import com.rieltor.infrastructure.config.JsonSettingsStore
import com.rieltor.infrastructure.database.repository.CatalogRepository
import it.tdlight.Init
import it.tdlight.Log
import it.tdlight.Slf4JLogMessageHandler
import it.tdlight.client.*
import it.tdlight.jni.TdApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

/**
 * Telegram transport persists monitored messages immediately. CatalogIngestionService owns the durable grace period.
 * Bot API messages use [TelegramListingBot] independently.
 */
class TelegramClientAdapter(
    private val apiId: Int,
    private val apiHash: String,
    private val sessionDirectory: Path,
    private val monitoredTopics: Set<TelegramMonitoredTopic>,
    private val repository: CatalogRepository,
    private val settings: JsonSettingsStore,
    private val historyOnly: Boolean = false,
) : TelegramInboxSource {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<TelegramSourceState>(TelegramSourceState.Stopped)
    private val diagnostics = TelegramDiagnostics(monitoredTopics)
    private val started = AtomicBoolean(false)
    private val startupMonitoringLogged = AtomicBoolean(false)
    private var factory: SimpleTelegramClientFactory? = null
    private var client: SimpleTelegramClient? = null

    /** Album members arrive as separate messages; only the merged post becomes one listing. */
    private val albums = MediaAlbumCollector<TdApi.Message>(
        scope = scope,
        settleDelayMillis = ALBUM_SETTLE_DELAY_MILLIS,
        maxItemCount = MAX_SOURCE_PHOTOS,
        itemId = { it.id },
        onReady = { saveAlbum(it) },
    )

    val state: StateFlow<TelegramSourceState> = mutableState.asStateFlow()

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
            if (!historyOnly) {
                builder.addUpdateHandler(TdApi.UpdateNewMessage::class.java, ::onNewMessage)
                builder.addUpdateHandler(TdApi.UpdateMessageContent::class.java, ::onMessageContentUpdated)
                builder.addUpdateHandler(TdApi.UpdateDeleteMessages::class.java, ::onMessagesDeleted)
            }
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
                        delay(STARTUP_MONITORING_LOG_DELAY_MILLIS.milliseconds)
                        client?.let(diagnostics::logStartupSnapshot)
                        client?.let { telegram ->
                            monitoredTopics.map { it.chatId }.distinct().forEach { chatId ->
                                telegram.send(TdApi.GetChat(chatId)).whenComplete { chat, error ->
                                    if (error == null && chat != null) {
                                        settings.update { current -> current.copy(monitoredTelegramChats =
                                            current.monitoredTelegramChats.map {
                                                if (it.chatId == chatId && it.name.isBlank()) it.copy(name = chat.title) else it
                                            }) }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> {
                mutableState.value = TelegramSourceState.AwaitingAuthorization
                logger.warn(
                    "Telegram session needs confirmation in an already authorized Telegram app. " + "Use the short-lived QR/login link printed by TDLight; do not share it."
                )
            }

            is TdApi.AuthorizationStateWaitPhoneNumber, is TdApi.AuthorizationStateWaitCode, is TdApi.AuthorizationStateWaitPassword -> {
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

                logger.warn("Telegram TDLib session is closed")
            }

            else -> logger.debug("Telegram authorization state: {}", update.authorizationState.javaClass.simpleName)
        }
    }

    private fun onNewMessage(update: TdApi.UpdateNewMessage) {
        val message = update.message
        if (!isMonitored(message)) return

        logger.info("threadId={}, messageId={}| {}", message.messageThreadId, message.id, message.summary())
        save(message)
    }

    /** Reads whole chat histories once, filtering topics locally to include forum/general topics alike. */
    suspend fun importHistory(from: java.time.Instant, until: java.time.Instant): Long {
        check(historyOnly) { "History import requires historyOnly mode" }
        require(monitoredTopics.isNotEmpty()) { "No monitored Telegram topics configured" }
        withTimeout(300_000.milliseconds) { state.first { it == TelegramSourceState.Ready } }
        val telegram = checkNotNull(client)
        return TelegramHistoryImporter(monitoredTopics, { chatId, cursor ->
            withContext(Dispatchers.IO) {
                telegram.send(TdApi.GetChatHistory(chatId, cursor, 0, 100, false))
                    .get(60, TimeUnit.SECONDS).messages.filterNotNull().toList()
            }
        }, ::save).run(from, until).also { albums.flush() }
    }

    private fun save(message: TdApi.Message) {
        // An album is persisted once, after its members settle, so its photos stay with the caption.
        if (message.mediaAlbumId != 0L) albums.add(message.mediaAlbumId, message) else persist(snapshot(message))
    }

    private fun saveAlbum(album: List<TdApi.Message>) {
        val ordered = album.sortedBy { it.id }
        // The caption carries the listing; the other members only contribute photos.
        val primary = ordered.firstOrNull { it.messageText().isNotBlank() } ?: ordered.first()
        logger.info(
            "album messageId={}, members={}, photos={}",
            primary.id,
            ordered.size,
            ordered.sumOf { photosOf(it.content, it.id).size },
        )
        persist(snapshot(primary, ordered))
    }

    private fun persist(message: SourceMessage) {
        repository.receive(
            message, System.currentTimeMillis(), settings.snapshot().stabilityWindowMinutes * 60_000
        )
    }

    private fun onMessageContentUpdated(update: TdApi.UpdateMessageContent) {
        val existing = repository.source(update.chatId, update.messageId) ?: return
        // Persist the edited content immediately; a subsequent refresh supplies Telegram's edit date.
        val text = when (val content = update.newContent) {
            is TdApi.MessageText -> content.text.textWithEmbeddedLinks()
            is TdApi.MessagePhoto -> content.caption.textWithEmbeddedLinks()
            else -> ""
        }
        val messageId = requireNotNull(existing.messageId)
        // An edit only replaces the content of one message; the known album stays authoritative.
        val photos = photosOf(update.newContent, messageId).ifEmpty { storedPhotos(existing.sourcePhotos) }
        persist(
            SourceMessage(
                existing.chatId,
                messageId,
                existing.messageThreadId,
                text,
                update.newContent.toString(),
                existing.sourceCreatedAt,
                existing.sourceEditedAt,
                mediaIdentity(photos, update.newContent),
                existing.userId,
                photos,
            )
        )
    }

    private fun onMessagesDeleted(update: TdApi.UpdateDeleteMessages) {
        if (update.isPermanent && !update.fromCache) update.messageIds.forEach {
            repository.delete(update.chatId, it, System.currentTimeMillis())
        }
    }

    override suspend fun refresh(chatId: Long, messageId: Long): SourceRefresh {
        val telegram = client ?: return SourceRefresh.Unavailable
        if (state.value != TelegramSourceState.Ready) return SourceRefresh.Unavailable
        return try {
            val message = withContext(Dispatchers.IO) {
                telegram.send(TdApi.GetMessage(chatId, messageId)).get(30, TimeUnit.SECONDS)
            }
            SourceRefresh.Found(withKnownAlbum(message, snapshot(message)))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // A failed read alone cannot prove permanent deletion (lost chat access looks similar).
            SourceRefresh.Unavailable
        }
    }

    /**
     * A re-read returns one message, while its listing owns the whole album. Without the already
     * known members every refresh would look like an edit and drop the other photos.
     */
    private fun withKnownAlbum(message: TdApi.Message, refreshed: SourceMessage): SourceMessage {
        if (message.mediaAlbumId == 0L) return refreshed
        val known = storedPhotos(repository.source(message.chatId, message.id)?.sourcePhotos ?: "[]")
        if (known.isEmpty()) return refreshed
        val photos = (known + refreshed.photos).distinctBy { it.uniqueId }.take(MAX_SOURCE_PHOTOS)
        return refreshed.copy(photos = photos, mediaIdentity = mediaIdentity(photos, message.content))
    }

    private fun snapshot(message: TdApi.Message, album: List<TdApi.Message> = listOf(message)): SourceMessage {
        val photos = album.flatMap { photosOf(it.content, it.id) }
            .distinctBy { it.uniqueId }
            .take(MAX_SOURCE_PHOTOS)
        return SourceMessage(
            message.chatId,
            message.id,
            message.messageThreadId,
            message.messageText(),
            message.toString(),
            message.date.toLong() * 1000,
            message.editDate.toLong() * 1000,
            mediaIdentity(photos, message.content),
            (message.senderId as? TdApi.MessageSenderUser)?.userId,
            photos,
        )
    }

    /** Largest available size of every photo in the post, in Telegram order. */
    private fun photosOf(content: TdApi.MessageContent, messageId: Long): List<SourcePhoto> = when (content) {
        is TdApi.MessagePhoto -> listOfNotNull(
            content.photo.sizes.maxByOrNull { it.width.toLong() * it.height }?.let { size ->
                SourcePhoto(
                    remoteFileId = size.photo.remote.id,
                    uniqueId = size.photo.remote.uniqueId,
                    width = size.width,
                    height = size.height,
                    fileSize = maxOf(size.photo.size, size.photo.expectedSize),
                    messageId = messageId,
                )
            }
        )

        else -> emptyList()
    }

    private fun storedPhotos(serialized: String): List<SourcePhoto> =
        runCatching { Json.decodeFromString<List<SourcePhoto>>(serialized) }.getOrDefault(emptyList())

    private fun mediaIdentity(photos: List<SourcePhoto>, content: TdApi.MessageContent): String =
        if (photos.isEmpty()) content.javaClass.simpleName else photos.joinToString { it.identity }

    override suspend fun downloadPhoto(photo: SourcePhoto): ByteArray? {
        val telegram = client ?: return null
        if (state.value != TelegramSourceState.Ready) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val remote = telegram.send(
                    TdApi.GetRemoteFile().apply { remoteFileId = photo.remoteFileId }
                ).get(30, TimeUnit.SECONDS)
                val download = TdApi.DownloadFile().apply {
                    fileId = remote.id
                    priority = PHOTO_DOWNLOAD_PRIORITY
                    synchronous = true
                }
                val file = if (remote.local?.isDownloadingCompleted == true) remote
                else telegram.send(download).get(PHOTO_DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                val path = file.local?.path?.takeIf { it.isNotBlank() } ?: return@runCatching null
                val bytes = Files.readAllBytes(Path.of(path))
                // The catalog keeps its own copy; the TDLib cache must not grow with every listing.
                runCatching { telegram.send(TdApi.DeleteFile().apply { fileId = file.id }) }
                bytes
            }.onFailure {
                logger.warn(
                    "Could not download Telegram photo. uniqueId={}, reason={}",
                    photo.uniqueId,
                    it.message ?: it.javaClass.simpleName,
                )
            }.getOrNull()
        }
    }

    private fun isMonitored(message: TdApi.Message): Boolean =
        monitoredTopics.any { monitored -> monitored.matches(message.chatId, message.messageThreadId) }

    override fun close() {

        albums.close()
        scope.cancel()

        runCatching { client?.closeAndWait() }.onFailure { logger.warn("Could not close Telegram client cleanly", it) }
        runCatching { factory?.close() }.onFailure {
                logger.warn(
                    "Could not close Telegram client factory cleanly",
                    it
                )
            }
        client = null
        factory = null
        mutableState.value = TelegramSourceState.Stopped
    }

    private fun Throwable.failureReason(): String = message?.takeIf(String::isNotBlank) ?: javaClass.simpleName

    private companion object {
        const val STARTUP_MONITORING_LOG_DELAY_MILLIS = 500L
        const val ALBUM_SETTLE_DELAY_MILLIS = 3_000L
        const val MAX_SOURCE_PHOTOS = 100
        const val PHOTO_DOWNLOAD_PRIORITY = 16
        const val PHOTO_DOWNLOAD_TIMEOUT_SECONDS = 120L
    }
}


internal enum class TelegramRefreshFailure {
    MESSAGE_NOT_FOUND, TIMEOUT,
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

internal fun TdApi.Message.messageText(): String = when (val content = content) {
    is TdApi.MessageText -> content.text.textWithEmbeddedLinks()
    is TdApi.MessagePhoto -> content.caption.textWithEmbeddedLinks()
    is TdApi.MessageVideo -> content.caption.textWithEmbeddedLinks()
    is TdApi.MessageDocument -> content.caption.textWithEmbeddedLinks()
    else -> ""
}

internal fun TdApi.Message.summary(): String = when (val content = content) {
    is TdApi.MessageText -> content.text.text.replace('\n', ' ')
    is TdApi.MessagePhoto -> "photo: ${content.caption.text}".replace('\n', ' ')
    else -> content.javaClass.simpleName.removePrefix("Message")
}

internal fun TdApi.FormattedText.textWithEmbeddedLinks(): String {
    val embeddedLinks = entities.asSequence()
        .mapNotNull { (it.type as? TdApi.TextEntityTypeTextUrl)?.url }
        .filter { it.isNotBlank() && !text.contains(it) }
        .distinct()
        .toList()
    return if (embeddedLinks.isEmpty()) text else (listOf(text) + embeddedLinks).joinToString("\n")
}
