package com.rieltor.domain.service

import com.rieltor.domain.model.ListingMessage
import com.rieltor.domain.model.MediaTextOverlay

/**
 * Converts an internal Telegram listing caption into a reusable public domain model.
 *
 * Source contacts, commissions and agency mentions are discarded before the public
 * model is built. The class is stateless so it can be tested independently from
 * Telegram and destination integrations.
 */
class ListingCaptionFormatter {
    fun filter(message: String?): ListingMessage? {
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
        val addressIndex = lines.indices.firstOrNull { index ->
            index != titleIndex && looksLikeAddress(lines[index])
        }
        val locationIndex = (0 until titleIndex).lastOrNull { index ->
            index != addressIndex && looksLikeLocation(lines[index])
        }
        val location = locationIndex?.let(lines::get)
        val title = buildTitle(lines[titleIndex], location)

        val content = lines.filterIndexed { index, _ ->
            index != titleIndex && index != locationIndex && index != addressIndex
        }
        val priceIndex = content.indexOfFirst(priceLine::containsMatchIn)
        val price = content.getOrNull(priceIndex)?.let(::extractPrice) ?: PRICE_ON_REQUEST
        val governmentPrograms = content.firstOrNull(governmentProgramsLine::containsMatchIn)
            ?.let(::normalizeGovernmentPrograms)
        val registration = content.firstOrNull(registrationLine::containsMatchIn)
            ?.let(::normalizeRegistration)
        val excluded = setOfNotNull(
            priceIndex.takeIf { it >= 0 },
            content.indexOfFirst(governmentProgramsLine::containsMatchIn).takeIf { it >= 0 },
            content.indexOfFirst(registrationLine::containsMatchIn).takeIf { it >= 0 },
        )
        val details = content.filterIndexed { index, _ -> index !in excluded }
        val (parameters, description) = details.partition(::looksLikeParameter)
        val fullText = lines.joinToString(" ")

        return ListingMessage(
            title = title,
            price = price,
            address = addressIndex?.let(lines::get)?.removeListMarker(),
            keyParameters = parameters.map { it.removeListMarker() },
            additionalParameters = description.map { it.removeListMarker() },
            governmentPrograms = governmentPrograms,
            registration = registration,
            hashtags = buildHashtags(fullText),
            phone = PUBLIC_PHONE,
        )
    }

    fun forTikTok(listing: ListingMessage?): String? {
        listing ?: return null

        return buildList {
            add("$TITLE_PREFIX${listing.title}")
            listing.address?.let { add("📍 $it") }
            add("💰 ${listing.price}")
            if (listing.keyParameters.isNotEmpty()) {
                add("")
                listing.keyParameters.forEach { add("$ITEM_PREFIX$it") }
            }
            if (listing.additionalParameters.isNotEmpty()) {
                add("")
                listing.additionalParameters.forEach(::add)
            }
            listing.governmentPrograms?.let { add("🏦 $it") }
            listing.registration?.let { add("📄 $it") }
            add("")
            add("🤙 ${listing.phone} $PUBLIC_CONTACT_NAME")
            add("")
            add(listing.hashtags.joinToString(" "))
        }.joinToString("\n")
    }

    fun photoOverlay(listing: ListingMessage?): MediaTextOverlay? = listing?.let {
        MediaTextOverlay(it.title, it.price, "${it.phone} $PUBLIC_CONTACT_NAME")
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

    private fun containsLocation(line: String): Boolean = cityWords.containsMatchIn(line)

    private fun looksLikeAddress(line: String): Boolean = addressWords.containsMatchIn(line)

    private fun looksLikeParameter(line: String): Boolean = parameterWords.containsMatchIn(line)

    private fun extractPrice(line: String): String = priceValue.find(line)?.groupValues?.get(1)
        ?.replace(repeatedWhitespace, " ")
        ?.trim()
        ?: line.removeListMarker()

    private fun normalizeGovernmentPrograms(line: String): String =
        line.removeListMarker().replace(fieldSeparator, ": ").trim()

    private fun normalizeRegistration(line: String): String =
        line.removeListMarker().replace(fieldSeparator, ": ").trim()

    private fun buildHashtags(fullText: String): List<String> {
        val tags = linkedSetOf("#нерухомість", "#продажнерухомості")
        propertyHashtags.firstOrNull { it.first.containsMatchIn(fullText) }?.let { tags += it.second }
        locationHashtags.filter { it.first.containsMatchIn(fullText) }.take(2).forEach { tags += it.second }
        FALLBACK_HASHTAGS.forEach { if (tags.size < HASHTAG_COUNT) tags += it }
        return tags.take(HASHTAG_COUNT)
    }

    private fun String.removeListMarker(): String = replaceFirst(listMarker, "").trim()

    private companion object {
        const val PUBLIC_PHONE = "066-372-71-02"
        const val PUBLIC_CONTACT_NAME = "Ірина"
        const val PRICE_ON_REQUEST = "Ціна за запитом"
        const val HASHTAG_COUNT = 5
        const val TITLE_PREFIX = "🏠 "
        const val ITEM_PREFIX = "• "
        val FALLBACK_HASHTAGS = listOf("#рієлтор", "#нерухомістьУкраїни", "#купитинерухомість")

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
        val cityWords = Regex(
            """(?iu)(?:ірпін\p{L}*|буч\p{L}*|гостомел\p{L}*|горенич\p{L}*|стоянк\p{L}*|софіївськ\p{L}*|михайлівц\p{L}*|северинівк\p{L}*|гнатівк\p{L}*)"""
        )
        val addressWords = Regex(
            """(?iu)(?:(?:^|\s)(?:вул\.?|вулиц\p{L}*|пров\.?|провул\p{L}*|просп\.?|проспект\p{L}*)\s|(?:^|\s)жк(?:\s|$))"""
        )
        val parameterWords = Regex(
            """(?iu)(?:ціна|вартість|площа|м\s*[²2]|м\.?\s*кв\.?|кв\.?\s*м|сот\p{L}*|ділянк\p{L}*|поверх\p{L}*|кімнат\p{L}*|санвуз\p{L}*|(?:^|\s)жк(?:\s|$)|(?:^|\s)вул\.?\s|вулиц\p{L}*|опален\p{L}*|комунікаці\p{L}*|вода|каналізаці\p{L}*|септик|свердловин\p{L}*|скважин\p{L}*|газ|електр\p{L}*|програм\p{L}*|сертифікат|іпотек\p{L}*|розтермінув\p{L}*)"""
        )
        val priceLine = Regex("""(?iu)(?:ціна|вартість|від\s+\d|\d[\d\s.,]*\s*(?:[$€₴]|грн\.?|usd|eur))""")
        val priceValue = Regex(
            """(?iu)(?:(?:ціна|вартість)\s*[-:.]?\s*)?((?:від\s*)?\d[\d\s.,]*(?:[$€₴]|грн\.?|usd|eur))"""
        )
        val governmentProgramsLine = Regex("""(?iu)(?:держ(?:авні|\.)?\s*програм\p{L}*|єосел\p{L}*|сертифікат|постанова)""")
        val registrationLine = Regex("""(?iu)(?:оформлення|оф\.?(?=\s)|переуступка)""")
        val fieldSeparator = Regex("""\s*[-–—:]\s*""")

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
