package com.rieltor.domain.service

/** Extracts supported Google Drive URLs from the original Telegram text. */
class GoogleDriveLinkExtractor {
    fun extract(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return driveUrl.findAll(text)
            .map { match -> match.value.trimEnd('.', ',', ';', ')', ']', '}') }
            .distinct()
            .toList()
    }

    private companion object {
        val driveUrl = Regex(
            """https?://(?:www\.)?drive\.google\.com/(?:drive/(?:u/\d+/)?folders/[A-Za-z0-9_-]+|file/d/[A-Za-z0-9_-]+|open\?[^\s]+|uc\?[^\s]+)""",
            RegexOption.IGNORE_CASE,
        )
    }
}
