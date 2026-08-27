package com.rieltor.infrastructure.telegram

import com.rieltor.application.PhotoRepostService
import com.rieltor.domain.model.RepostResult
import com.rieltor.domain.model.TelegramPhotoMessage
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
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Telegram user client backed by the copied TDLib session. No Bot API is used. */
class TelegramClientAdapter(
    private val apiId: Int,
    private val apiHash: String,
    private val sessionDirectory: Path,
    private val allowedSenderId: Long,
    private val repostService: PhotoRepostService,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var factory: SimpleTelegramClientFactory? = null
    private var client: SimpleTelegramClient? = null

    fun start() {
        require(Files.isDirectory(sessionDirectory.resolve("data"))) {
            "TDLib session is missing: ${sessionDirectory.toAbsolutePath()}"
        }
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
            "Telegram TDLib client started. session={}, allowedSenderId={}",
            sessionDirectory.toAbsolutePath(),
            allowedSenderId,
        )
    }

    private fun onAuthorizationState(update: TdApi.UpdateAuthorizationState) {
        when (update.authorizationState) {
            is TdApi.AuthorizationStateReady -> logger.info("Telegram TDLib session is authorized and ready")
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
        val sender = message.senderId as? TdApi.MessageSenderUser ?: return
        if (sender.userId != allowedSenderId || message.chatId != allowedSenderId) return
        val photoContent = message.content as? TdApi.MessagePhoto ?: return
        val largestPhoto = photoContent.photo.sizes.maxByOrNull { size ->
            size.photo.expectedSize.takeIf { it > 0 } ?: (size.width.toLong() * size.height.toLong())
        } ?: return
        val telegramClient = client ?: return

        scope.launch {
            runCatching {
                val downloaded = telegramClient.send(
                    TdApi.DownloadFile(largestPhoto.photo.id, 1, 0, 0, true)
                ).get(2, TimeUnit.MINUTES)
                val localPath = downloaded.local?.path
                    ?.takeIf { it.isNotBlank() }
                    ?.let(Path::of)
                    ?: error("TDLib did not return a local photo path")
                val updateId = java.lang.Long.rotateLeft(message.chatId, 17) xor message.id
                Files.newInputStream(localPath).let { stream ->
                    repostService.handle(
                        TelegramPhotoMessage(
                            updateId = updateId,
                            senderId = sender.userId,
                            chatId = message.chatId,
                            caption = photoContent.caption?.text,
                            fileName = localPath.fileName.toString(),
                            content = stream,
                        )
                    )
                }
            }.onSuccess { result ->
                when (result) {
                    is RepostResult.Published -> logger.info(
                        "Telegram photo submitted to TikTok. publishId={}, privacy={}",
                        result.receipt.publishId,
                        result.receipt.privacyLevel,
                    )
                    RepostResult.Duplicate -> logger.info("Telegram photo message was already processed")
                    RepostResult.IgnoredSender -> Unit
                }
            }.onFailure { error ->
                logger.error("Failed to repost Telegram message {} to TikTok", message.id, error)
            }
        }
    }

    override fun close() {
        scope.cancel()
        runCatching { client?.closeAndWait() }
            .onFailure { logger.warn("Could not close Telegram client cleanly", it) }
        factory?.close()
        client = null
        factory = null
    }
}
