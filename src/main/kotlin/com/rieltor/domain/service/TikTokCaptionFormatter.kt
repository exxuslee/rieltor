package com.rieltor.domain.service

import com.rieltor.domain.model.MediaTextOverlay

/**
 * Converts an internal Telegram listing caption into a reusable public description.
 *
 * Contact and commercial terms from the source are deliberately discarded before the
 * public contact is appended. The class is stateless so it can be tested independently
 * from Telegram and destination integrations.
 */
class ListingCaptionFormatter {
    fun filter(message: String?): String? {
        if (message.isNullOrBlank()) return null

        val sourceLines = message
            .replace('\r', '\n')
            .replace('\u00A0', ' ')
            .lineSequence()
            .map(String::trim)
            .toList()
        val lines = sourceLines
            .mapIndexed { index, line ->
                val nextToPhone = sourceLines.getOrNull(index - 1)?.let(phone::containsMatchIn) == true ||
                    sourceLines.getOrNull(index + 1)?.let(phone::containsMatchIn) == true
                if (nextToPhone && contactNameOnly.matches(line)) "" else cleanLine(line)
            }
            .filter(String::isNotBlank)
            .toMutableList()

        if (lines.isEmpty()) return null

        val titleIndex = lines.indexOfFirst(::looksLikePropertyTitle).takeIf { it >= 0 } ?: 0
        val locationIndex = (0 until titleIndex).lastOrNull { looksLikeLocation(lines[it]) }
        val location = locationIndex?.let(lines::get)
        val title = buildTitle(lines[titleIndex], location)

        val content = lines.filterIndexed { index, _ -> index != titleIndex && index != locationIndex }
        val (parameters, description) = content.partition(::looksLikeParameter)

        return buildList {
            add("🏠 $title")
            if (parameters.isNotEmpty()) {
                add("")
                parameters.forEach { add("• ${it.removeListMarker()}") }
            }
            if (description.isNotEmpty()) {
                add("")
                description.forEach { add(it.removeListMarker()) }
            }
            add("")
            add(PUBLIC_CONTACT)
            add("")
            add(buildHashtags(lines.joinToString(" ")))
        }.joinToString("\n")
    }

    fun photoOverlay(filteredCaption: String?): MediaTextOverlay? {
        if (filteredCaption.isNullOrBlank()) return null

        val lines = filteredCaption.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        val title = lines.firstOrNull { it.startsWith(TITLE_PREFIX) }
            ?.removePrefix(TITLE_PREFIX)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return null
        val price = lines.asSequence()
            .filterNot { it.startsWith(TITLE_PREFIX) || it == PUBLIC_CONTACT || it.startsWith('#') }
            .map { it.removePrefix(ITEM_PREFIX).trim() }
            .firstOrNull(priceLine::containsMatchIn)

        return MediaTextOverlay(title, price, PUBLIC_CONTACT.substringAfter(' '))
    }

    private fun cleanLine(source: String): String {
        var line = source.trim()
        if (line.isBlank() || phone.containsMatchIn(line)) return ""
        if (internalNoise.matches(line) || standalonePercentage.matches(line)) return ""

        line = googleUrl.replace(line, "")
        line = agency.replace(line, "")
        line = priceParentheticalNote.replace(line, "$1")
        line = parenthesizedCommission.replace(line, "")
        line = commissionTail.replace(line, "")
        line = registrationPercentageTail.replace(line, "")
        line = repeatedWhitespace.replace(line, " ").trim().trimEnd(',', ';', '-', '—')

        return line.takeUnless { it.isBlank() || internalNoise.matches(it) } ?: ""
    }

    private fun buildTitle(titleLine: String, location: String?): String {
        val cleanTitle = titleLine.removeListMarker().trim().trimEnd('.', ',', ':')
        if (location == null || containsLocation(cleanTitle)) return cleanTitle
        return "$cleanTitle — ${location.trim().trimEnd('.', ',', ':')}"
    }

    private fun looksLikePropertyTitle(line: String): Boolean = propertyType.containsMatchIn(line)

    private fun looksLikeLocation(line: String): Boolean =
        locationWords.containsMatchIn(line) ||
            (line.length <= 60 && !line.any(Char::isDigit) && !propertyType.containsMatchIn(line))

    private fun containsLocation(line: String): Boolean = locationWords.containsMatchIn(line)

    private fun looksLikeParameter(line: String): Boolean = parameterWords.containsMatchIn(line)

    private fun buildHashtags(fullText: String): String {
        val tags = linkedSetOf("#нерухомість", "#продажнерухомості")
        propertyHashtags.firstOrNull { it.first.containsMatchIn(fullText) }?.let { tags += it.second }
        locationHashtags.filter { it.first.containsMatchIn(fullText) }.take(2).forEach { tags += it.second }
        tags += "#ІринаЛіннік"
        return tags.joinToString(" ")
    }

    private fun String.removeListMarker(): String = replaceFirst(listMarker, "").trim()

    private companion object {
        const val PUBLIC_CONTACT = "🤙 066-372-71-02 Ірина"
        const val TITLE_PREFIX = "🏠 "
        const val ITEM_PREFIX = "• "

        val googleUrl = Regex("""(?iu)https?://(?:drive|docs)\.google\.com/\S+""")
        val phone = Regex("""(?<!\d)(?:\+?38[\s().-]*)?0\d{2}(?:[\s().-]*\d){7}(?!\d)""")
        val agency = Regex("""(?iu)(?<!\p{L})АН\s*[«\"']?\s*(?:НОВАТОР|NOVATOR)\s*[»\"']?|(?<!\p{L})(?:АН\s+)?НОВАТОР(?!\p{L})""")
        val parenthesizedCommission = Regex(
            """(?iu)\s*\(\s*\d[\d\s.,]*\s*(?:[$€]|%)\s*[/\\]\s*2\s*\)"""
        )
        val priceParentheticalNote = Regex(
            """(?iu)((?:ціна|вартість)\s*[:.]?\s*(?:від\s*)?\d[\d\s.,]*\s*(?:[$€₴]|грн\.?))(?:\s*\([^\r\n)]*\))+"""
        )
        val commissionTail = Regex(
            """(?iu)\s*(?:\(?\s*)?(?:ваша\s+)?(?:коміс(?:ія|ії)?|комиссия|ком(?:\.|(?=\s*:?\s*\d)))\s*:?.*$"""
        )
        val registrationPercentageTail = Regex(
            """(?iu)\s*(?:оформлення|оф\.?(?=\s)|переуступка)\s*:?.*?\d[\d.,]*\s*%.*$"""
        )
        val standalonePercentage = Regex("""^\s*\d[\d.,]*\s*%\s*$""")
        val internalNoise = Regex(
            """(?iu)^\s*(?:продаж|новий\s+об['ʼ’]?єкт!?|ексклюзив|терміново!?|без\s+реклами\s*!*|бартер)\s*$"""
        )
        val repeatedWhitespace = Regex("""[\t ]{2,}""")
        val listMarker = Regex("""^[\-–—•*]+\s*""")
        val contactNameOnly = Regex(
            """(?iu)^\s*[\p{L}][\p{L}'ʼ’.-]*(?:\s+[\p{L}][\p{L}'ʼ’.-]*){0,2}(?:\s*,?\s+АН\s+НОВАТОР)?\s*$"""
        )
        val propertyType = Regex(
            """(?iu)(?:таунхаус\p{L}*|дуплекс\p{L}*|квартир\p{L}*|будинок|будинки|ділянк\p{L}*|комерці\p{L}*|офіс\p{L}*)"""
        )
        val locationWords = Regex(
            """(?iu)(?:ірпін\p{L}*|буч\p{L}*|гостомел\p{L}*|горенич\p{L}*|стоянк\p{L}*|софіївськ\p{L}*|михайлівц\p{L}*|северинівк\p{L}*|гнатівк\p{L}*|(?:^|\s)жк(?:\s|$)|(?:^|\s)вул\.?\s|вулиц\p{L}*)"""
        )
        val parameterWords = Regex(
            """(?iu)(?:ціна|вартість|площа|м\s*[²2]|м\.?\s*кв\.?|кв\.?\s*м|сот\p{L}*|ділянк\p{L}*|поверх\p{L}*|кімнат\p{L}*|санвуз\p{L}*|(?:^|\s)жк(?:\s|$)|(?:^|\s)вул\.?\s|вулиц\p{L}*|опален\p{L}*|комунікаці\p{L}*|вода|каналізаці\p{L}*|септик|свердловин\p{L}*|скважин\p{L}*|газ|електр\p{L}*|програм\p{L}*|сертифікат|іпотек\p{L}*|розтермінув\p{L}*)"""
        )
        val priceLine = Regex("""(?iu)(?:ціна|вартість|від\s+\d|\d[\d\s.,]*\s*(?:[$€₴]|грн\.?|usd|eur))""")

        val propertyHashtags = listOf(
            Regex("(?iu)таунхаус") to "#таунхаус",
            Regex("(?iu)дуплекс") to "#дуплекс",
            Regex("(?iu)квартир|\b[123]-?к\b|\b[123]кк\b") to "#квартира",
            Regex("(?iu)будинок|будинки") to "#будинок",
            Regex("(?iu)ділянк") to "#земельнаділянка",
            Regex("(?iu)комерці|офіс") to "#комерційнанерухомість",
        )
        val locationHashtags = listOf(
            Regex("(?iu)ірпін") to "#Ірпінь",
            Regex("(?iu)буч") to "#Буча",
            Regex("(?iu)гостомел") to "#Гостомель",
            Regex("(?iu)софіївськ") to "#СофіївськаБорщагівка",
            Regex("(?iu)горенич") to "#Гореничі",
            Regex("(?iu)стоянк") to "#Стоянка",
            Regex("(?iu)михайлівц") to "#МихайлівкаРубежівка",
        )
    }
}
