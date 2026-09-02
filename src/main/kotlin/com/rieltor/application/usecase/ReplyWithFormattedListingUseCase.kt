package com.rieltor.application.usecase

import com.rieltor.application.port.TelegramBotIncomingMessage
import com.rieltor.application.port.TelegramBotReplySender
import com.rieltor.domain.model.TelegramPhoto
import com.rieltor.domain.repository.ExternalPhotoSource
import com.rieltor.domain.service.GoogleDriveLinkExtractor
import com.rieltor.domain.service.ListingCaptionFormatter
import kotlinx.coroutines.CancellationException

class ReplyWithFormattedListingUseCase(
    private val externalPhotoSource: ExternalPhotoSource,
    private val replySender: TelegramBotReplySender,
    private val captionFormatter: ListingCaptionFormatter = ListingCaptionFormatter(),
    private val driveLinkExtractor: GoogleDriveLinkExtractor = GoogleDriveLinkExtractor(),
    private val maxPhotoCount: Int = DEFAULT_MAX_PHOTO_COUNT,
) {
    init {
        require(maxPhotoCount > 0) { "maxPhotoCount must be positive" }
    }

    suspend fun execute(message: TelegramBotIncomingMessage) {
        val listing = captionFormatter.filter(message.text)
        val formattedText = captionFormatter.forTikTok(listing)
        if (formattedText == null) {
            replySender.sendText(
                message.chatId,
                message.messageThreadId,
                message.messageId,
                INVALID_MESSAGE_TEXT,
            )
            return
        }

        val driveLinks = driveLinkExtractor.extract(message.text)
        if (driveLinks.isEmpty()) {
            replySender.sendText(
                message.chatId,
                message.messageThreadId,
                message.messageId,
                MISSING_DRIVE_LINK_TEXT,
            )
            return
        }

        val photos = try {
            externalPhotoSource.downloadPhotos(driveLinks, maxPhotoCount)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            replySender.sendText(
                message.chatId,
                message.messageThreadId,
                message.messageId,
                DRIVE_DOWNLOAD_ERROR_TEXT,
            )
            return
        }
        if (photos.isEmpty()) {
            replySender.sendText(
                message.chatId,
                message.messageThreadId,
                message.messageId,
                DRIVE_DOWNLOAD_ERROR_TEXT,
            )
            return
        }

        try {
            formattedText.splitForTelegram().forEachIndexed { index, part ->
                replySender.sendText(
                    chatId = message.chatId,
                    messageThreadId = message.messageThreadId,
                    replyToMessageId = message.messageId.takeIf { index == 0 },
                    text = part,
                )
            }
            photos.chunked(TELEGRAM_MEDIA_GROUP_LIMIT).forEach { batch ->
                replySender.sendPhotos(message.chatId, message.messageThreadId, batch)
            }
        } finally {
            photos.closeAll()
        }
    }

    private fun String.splitForTelegram(): List<String> {
        if (length <= TELEGRAM_TEXT_LIMIT) return listOf(this)

        val result = mutableListOf<String>()
        var start = 0
        while (start < length) {
            var end = minOf(start + TELEGRAM_TEXT_LIMIT, length)
            if (end < length && Character.isHighSurrogate(this[end - 1]) && Character.isLowSurrogate(this[end])) {
                end--
            }
            val lineBreak = lastIndexOf('\n', end - 1)
                .takeIf { it >= start + TELEGRAM_TEXT_LIMIT / 2 }
            lineBreak?.let { end = it }
            result += substring(start, end)
            start = end
            while (start < length && this[start] == '\n') start++
        }
        return result
    }

    private fun List<TelegramPhoto>.closeAll() {
        forEach { photo -> runCatching { photo.content.close() } }
    }

    private companion object {
        const val TELEGRAM_MEDIA_GROUP_LIMIT = 10
        const val TELEGRAM_TEXT_LIMIT = 4_096
        const val DEFAULT_MAX_PHOTO_COUNT = 100
        const val INVALID_MESSAGE_TEXT =
            "Надішліть текст оголошення з посиланням на файл або папку Google Drive."
        const val MISSING_DRIVE_LINK_TEXT = "У повідомленні не знайдено посилання на Google Drive."
        const val DRIVE_DOWNLOAD_ERROR_TEXT =
            "Не вдалося завантажити фото з Google Drive. Перевірте посилання та доступ до файлів."
    }
}
