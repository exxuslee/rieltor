package com.rieltor.infrastructure.google

/** Extracts supported Google Drive URLs from the original Telegram text. */
class GoogleDriveLinkExtractor {
    /** Stable identity across folder/file URLs and open?id= / uc?id= aliases. */
    fun identities(urls: List<String>): Set<String> = urls.mapNotNull { url ->
        Regex("(?:/folders/|/file/d/|[?&]id=)([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1)
    }.toSet()

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
