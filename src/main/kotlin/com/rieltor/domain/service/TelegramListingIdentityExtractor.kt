package com.rieltor.domain.service

import com.rieltor.domain.model.TelegramRepostKey

/** Extracts a stable listing identity from the original, unfiltered Telegram caption. */
class TelegramListingIdentityExtractor {
    fun extract(messageThreadId: Long, caption: String?): TelegramRepostKey? {
        if (caption.isNullOrBlank()) return null

        val lines = caption
            .replace('\r', '\n')
            .replace('\u00A0', ' ')
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toList()
        val price = lines.asSequence().mapNotNull(::extractPrice).firstOrNull() ?: return null
        val address = extractAddress(lines) ?: return null

        return TelegramRepostKey(messageThreadId, price, address)
    }

    private fun extractPrice(line: String): String? {
        val match = labeledPrice.find(line) ?: return null
        val amount = match.groupValues[1].filter(Char::isDigit).trimStart('0').ifEmpty { "0" }
        val currency = normalizeCurrency(match.groupValues[2])
        return if (currency.isEmpty()) amount else "$amount:$currency"
    }

    private fun extractAddress(lines: List<String>): String? {
        lines.forEach { line ->
            labeledAddress.matchEntire(line)?.groupValues?.get(1)?.let { address ->
                return normalizeAddress(address)
            }
        }
        lines.forEach { line ->
            streetAddress.find(line)?.value?.let { address ->
                return normalizeAddress(address)
            }
        }
        lines.forEach { line ->
            if (labeledPrice.containsMatchIn(line) || nonAddressLine.containsMatchIn(line)) return@forEach
            implicitAddress.find(line)?.value?.let { address ->
                return normalizeAddress(address)
            }
        }
        return null
    }

    private fun normalizeCurrency(source: String): String = when (source.trim().lowercase().trimEnd('.')) {
        "$", "usd", "дол", "долар", "долари", "доларів" -> "USD"
        "€", "eur", "євро", "евро" -> "EUR"
        "₴", "uah", "грн", "гривня", "гривні", "гривень" -> "UAH"
        else -> source.trim().uppercase()
    }

    private fun normalizeAddress(source: String): String {
        val address = streetAddress.find(source)?.value ?: source
        return address
            .lowercase()
            .replace('ё', 'е')
            .replace(streetPrefix, " ")
            .replace(buildingPrefix, " ")
            .replace(nonAddressCharacter, " ")
            .replace(repeatedWhitespace, " ")
            .trim()
    }

    private companion object {
        val labeledPrice = Regex(
            """(?iu)(?:ціна|цена|вартість|стоимость)\s*[.:=\-–—]?\s*(?:від|от)?\s*([0-9][0-9\s\u00A0.,']*)(?:\s*(\$|€|₴|USD|EUR|UAH|дол(?:ар(?:и|ів)?)?|євро|евро|грн\.?|грив(?:ня|ні|ень)))?"""
        )
        val labeledAddress = Regex("""(?iu)^\s*(?:адреса?|адрес)\s*[:.=\-]\s*(.+?)\s*$""")
        val streetAddress = Regex(
            """(?iu)(?:вул(?:иця)?\.?|ул(?:ица)?\.?|пров(?:улок)?\.?|пер(?:еулок)?\.?|просп(?:ект)?\.?|бульв(?:ар)?\.?|шосе|набережн\p{L}*)\s+.+$"""
        )
        val implicitAddress = Regex(
            """(?u)[\p{Lu}][\p{L}'ʼ’\-]+(?:\s+[\p{Lu}][\p{L}'ʼ’\-]+){0,2}\s+\d+[\p{L}\d/\-]*"""
        )
        val streetPrefix = Regex(
            """(?iu)^\s*(?:вул(?:иця)?|ул(?:ица)?|пров(?:улок)?|пер(?:еулок)?|просп(?:ект)?|бульв(?:ар)?|шосе|набережн\p{L}*)\.?\s*"""
        )
        val buildingPrefix = Regex("""(?iu)(?:^|\s)(?:буд(?:инок)?|дом)\.?\s*""")
        val nonAddressLine = Regex(
            """(?iu)(?:площа|площадь|\bм\s*[²2]\b|кімнат|комнат|поверх|этаж|телефон|коміс|комисс|оформлення)"""
        )
        val nonAddressCharacter = Regex("""[^\p{L}\d]+""")
        val repeatedWhitespace = Regex("""\s+""")
    }
}
