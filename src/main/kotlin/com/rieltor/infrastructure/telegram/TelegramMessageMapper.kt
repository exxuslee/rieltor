package com.rieltor.infrastructure.telegram

import com.rieltor.domain.model.TelegramListing
import com.rieltor.domain.model.TelegramPhoto
import com.rieltor.domain.service.GoogleDriveLinkExtractor
import com.rieltor.domain.service.TelegramListingIdentityExtractor
import it.tdlight.client.SimpleTelegramClient
import it.tdlight.jni.TdApi
import kotlinx.coroutines.delay
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal class TelegramMessageMapper(
    private val identityExtractor: TelegramListingIdentityExtractor = TelegramListingIdentityExtractor(),
    private val driveLinkExtractor: GoogleDriveLinkExtractor = GoogleDriveLinkExtractor(),
) {
    suspend fun map(
        telegramClient: SimpleTelegramClient,
        messages: List<TdApi.Message>,
    ): TelegramListing? {
        val orderedMessages = messages.sortedBy { it.id }
        val firstMessage = orderedMessages.firstOrNull() ?: return null
        val caption = orderedMessages.asSequence()
            .mapNotNull { message ->
                when (val content = message.content) {
                    is TdApi.MessagePhoto -> content.caption.textWithEmbeddedLinks().trim()
                    is TdApi.MessageText -> content.text.textWithEmbeddedLinks().trim()
                    else -> null
                }
            }
            .firstOrNull { it.isNotBlank() }
        val photos = mutableListOf<TelegramPhoto>()

        try {
            orderedMessages.forEach { message ->
                val photoContent = message.content as? TdApi.MessagePhoto ?: return@forEach
                val largestPhoto = photoContent.photo.sizes.maxByOrNull { size ->
                    size.photo.expectedSize.takeIf { it > 0 } ?: (size.width.toLong() * size.height.toLong())
                } ?: error("Telegram photo has no downloadable sizes")
                val downloaded = downloadPhoto(telegramClient, largestPhoto.photo)
                val localPath = downloaded.local?.path
                    ?.takeIf { it.isNotBlank() }
                    ?.let(Path::of)
                    ?: error("TDLib did not return a local photo path")
                photos += TelegramPhoto(
                    fileName = localPath.fileName.toString(),
                    content = Files.newInputStream(localPath),
                    localPath = localPath.toAbsolutePath().normalize().toString(),
                )
            }
            val sourceId = firstMessage.mediaAlbumId.takeIf { it != 0L } ?: firstMessage.id
            return TelegramListing(
                updateId = java.lang.Long.rotateLeft(firstMessage.chatId, 17) xor sourceId,
                chatId = firstMessage.chatId,
                messageThreadId = firstMessage.messageThreadId,
                caption = caption,
                photos = photos.toList(),
                googleDriveLinks = driveLinkExtractor.extract(caption),
                repostKey = identityExtractor.extract(firstMessage.messageThreadId, caption),
                normalizedPrice = identityExtractor.extractNormalizedPrice(caption),
            )
        } catch (error: Throwable) {
            photos.forEach { photo -> runCatching { photo.content.close() } }
            throw error
        }
    }

    private suspend fun downloadPhoto(telegramClient: SimpleTelegramClient, file: TdApi.File): TdApi.File {
        if (file.hasUsableLocalCopy()) return file

        var lastError: Throwable? = null
        repeat(DOWNLOAD_MAX_ATTEMPTS) { attempt ->
            try {
                return telegramClient.send(TdApi.DownloadFile(file.id, DOWNLOAD_PRIORITY, 0, 0, true))
                    .get(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (error: java.util.concurrent.TimeoutException) {
                lastError = error
                if (attempt + 1 < DOWNLOAD_MAX_ATTEMPTS) delay(DOWNLOAD_RETRY_DELAY_MILLIS * (attempt + 1))
            }
        }
        throw IOException(
            "Telegram photo ${file.id} download timed out after $DOWNLOAD_MAX_ATTEMPTS attempts: " +
                (lastError?.message ?: "timeout"),
            lastError,
        )
    }

    private fun TdApi.File.hasUsableLocalCopy(): Boolean =
        local?.isDownloadingCompleted == true &&
            local.path.isNotBlank() &&
            runCatching { Files.isRegularFile(Path.of(local.path)) }.getOrDefault(false)

    private companion object {
        const val DOWNLOAD_PRIORITY = 32
        const val DOWNLOAD_TIMEOUT_SECONDS = 120L
        const val DOWNLOAD_MAX_ATTEMPTS = 3
        const val DOWNLOAD_RETRY_DELAY_MILLIS = 1_000L
    }
}

internal fun TdApi.FormattedText.textWithEmbeddedLinks(): String {
    val embeddedLinks = entities.asSequence()
        .mapNotNull { (it.type as? TdApi.TextEntityTypeTextUrl)?.url }
        .filter { it.isNotBlank() && !text.contains(it) }
        .distinct()
        .toList()
    return if (embeddedLinks.isEmpty()) text else (listOf(text) + embeddedLinks).joinToString("\n")
}

internal fun TdApi.Message.summary(): String = when (val content = content) {
    is TdApi.MessageText -> content.text.text.replace('\n', ' ')
    is TdApi.MessagePhoto -> "photo: ${content.caption.text}".replace('\n', ' ')
    else -> content.javaClass.simpleName.removePrefix("Message")
}
