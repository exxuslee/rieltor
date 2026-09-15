package com.rieltor.infrastructure.telegram

import com.rieltor.application.model.TelegramSourceState
import com.rieltor.application.port.TelegramInboxSource
import com.rieltor.domain.model.SourceMessage
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
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
) : TelegramInboxSource {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<TelegramSourceState>(TelegramSourceState.Stopped)
    private val diagnostics = TelegramDiagnostics(monitoredTopics)
    private val started = AtomicBoolean(false)
    private val startupMonitoringLogged = AtomicBoolean(false)
    private var factory: SimpleTelegramClientFactory? = null
    private var client: SimpleTelegramClient? = null

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
            builder.addUpdateHandler(TdApi.UpdateNewMessage::class.java, ::onNewMessage)
            builder.addUpdateHandler(TdApi.UpdateMessageContent::class.java, ::onMessageContentUpdated)
            builder.addUpdateHandler(TdApi.UpdateDeleteMessages::class.java, ::onMessagesDeleted)
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
        
                logger.warn("Telegram TDLib session is closed")
            }
            else -> logger.debug("Telegram authorization state: {}", update.authorizationState.javaClass.simpleName)
        }
    }

    private fun onNewMessage(update: TdApi.UpdateNewMessage) {
        if (isMonitored(update.message)) save(update.message)
    }

    private fun save(message: TdApi.Message) {
        repository.receive(snapshot(message), System.currentTimeMillis(), settings.snapshot().stabilityWindowMinutes * 60_000)
    }

    private fun onMessageContentUpdated(update: TdApi.UpdateMessageContent) {
        val existing = repository.source(update.chatId, update.messageId) ?: return
        // Persist the edited content immediately; a subsequent refresh supplies Telegram's edit date.
        val text = when (val content = update.newContent) {
            is TdApi.MessageText -> content.text.textWithEmbeddedLinks()
            is TdApi.MessagePhoto -> content.caption.textWithEmbeddedLinks()
            else -> ""
        }
        repository.receive(SourceMessage(existing.chatId, requireNotNull(existing.messageId), existing.messageThreadId,
            existing.mediaAlbumId, text, update.newContent.toString(), existing.sourceCreatedAt,
            existing.sourceEditedAt, mediaIdentity(update.newContent)), System.currentTimeMillis(),
            settings.snapshot().stabilityWindowMinutes * 60_000)
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
            SourceRefresh.Found(snapshot(message))
        } catch (error: CancellationException) { throw error }
        catch (error: Throwable) {
            // A failed read alone cannot prove permanent deletion (lost chat access looks similar).
            SourceRefresh.Unavailable
        }
    }

    private fun snapshot(message: TdApi.Message): SourceMessage {
        val text = when (val content = message.content) {
            is TdApi.MessageText -> content.text.textWithEmbeddedLinks()
            is TdApi.MessagePhoto -> content.caption.textWithEmbeddedLinks()
            else -> ""
        }
        return SourceMessage(message.chatId, message.id, message.messageThreadId, message.mediaAlbumId,
            text, message.toString(), message.date.toLong() * 1000, message.editDate.toLong() * 1000,
            mediaIdentity(message.content))
    }

    private fun mediaIdentity(content: TdApi.MessageContent): String = when (content) {
        is TdApi.MessagePhoto -> content.photo.sizes.joinToString { "${it.photo.remote.uniqueId}:${it.width}:${it.height}" }
        else -> content.javaClass.simpleName
    }
    private fun isMonitored(message: TdApi.Message): Boolean =
        monitoredTopics.any { monitored -> monitored.matches(message.chatId, message.messageThreadId) }

    override fun close() {


        scope.cancel()

        runCatching { client?.closeAndWait() }
            .onFailure { logger.warn("Could not close Telegram client cleanly", it) }
        runCatching { factory?.close() }
            .onFailure { logger.warn("Could not close Telegram client factory cleanly", it) }
        client = null
        factory = null
        mutableState.value = TelegramSourceState.Stopped
    }

    private fun Throwable.failureReason(): String = message?.takeIf(String::isNotBlank)
        ?: javaClass.simpleName

    private companion object {
        const val STARTUP_MONITORING_LOG_DELAY_MILLIS = 500L
    }
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


