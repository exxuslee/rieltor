package com.rieltor.domain.service

import com.rieltor.domain.model.*
import com.rieltor.infrastructure.database.model.IncomingEntity
import com.rieltor.infrastructure.database.model.ListingEntity
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.math.RoundingMode

class CatalogAdsParser(
    private val formatter: ListingCaptionFormatter = ListingCaptionFormatter(),
    private val priceNormalizer: CatalogPriceNormalizer = CatalogPriceNormalizer()
) {
    fun parse(row: IncomingEntity, type: String?, now: Long): ListingEntity {
        val clean = formatter.filter(row.rawText)
        val warnings = mutableListOf<String>()
        val matchedLocations = listOf(
            "IRPIN" to "ірп[іе]н|ирпен",
            "BUCHA" to "буч[аіи]",
            "VORZEL" to "ворзел|ворзель",
            "HOSTOMEL" to "гостомел",
            "STOYANKA" to "стоянк",
            "HORENYCHI" to "горенич",
            "MYKHAILIVKA_RUBEZHIVKA" to "михайл[іи]вк[аіи][ -]рубеж[іе]вк",
            "BILOHORODKA" to "б[іи]логородк",
            "DMYTRIVKA" to "дмитр[іи]вк"
        )
            .filter { Regex(it.second, RegexOption.IGNORE_CASE).containsMatchIn(row.rawText) }.map { it.first }
        val location = when (matchedLocations.size) {
            0 -> "OTHER"
            1 -> matchedLocations.single()
            else -> null
        }
        val priceMatch = extractPrice(row.rawText)
        val price = priceMatch?.amount
        val currency = priceMatch?.currency?.lowercase()?.let {
            when {
                it in setOf("usd", "$") || it.startsWith("долар") -> "USD"
                it in setOf("uah", "₴", "грн") -> "UAH"; else -> "EUR"
            }
        }
        if (price == null || price <= 0) warnings += "Missing or ambiguous price/currency"
        if (type !in CatalogCodes.types) warnings += "Configure topicTypeMapping for ${row.chatId}:${row.messageThreadId}"
        if (location == null) warnings += "Ambiguous location"
        val links = GoogleDriveLinkExtractor().extract(row.rawText)
        val telegramPhotos = runCatching { Json.decodeFromString<List<SourcePhoto>>(row.sourcePhotos) }
            .getOrDefault(emptyList())
        // Photos of the Telegram post are enough on their own: a Drive link is now optional.
        if (links.isEmpty() && telegramPhotos.isEmpty()) warnings += "Missing listing photos"
        if (clean == null) warnings += "Missing public content"
        val programs = extractGovernmentPrograms(row.rawText)
        fun decimal(pattern: String, source: String = row.rawText): Double? =
            Regex(pattern, RegexOption.IGNORE_CASE).find(source)?.groupValues?.get(1)?.replace(',', '.')
                ?.toDoubleOrNull()

        val areaText = row.rawText.lineSequence().filterNot { it == priceMatch?.line }.joinToString("\n")
        val area = extractArea(areaText)
        val rooms = CatalogCodes.apartmentRooms(type)
            ?: decimal("""(\d+)\s*[- ]?(?:кімнат|комнат)""")?.toInt()
        val floor = Regex("""(?iu)(?:поверх|этаж)\s*[:\-]?\s*(\d+)\s*(?:/|із|з|из)\s*(\d+)""").find(row.rawText)
        val transaction = if (Regex("(?iu)оренд|аренд").containsMatchIn(row.rawText)) "RENT" else "SALE"
        val priceSuffix = priceMatch?.suffix.orEmpty()
        val period = when {
            Regex("""(?iu)^\s*(?:/|за)\s*(?:1\s*)?(?:м[²2]|кв\.?\s*м|квадратн\p{L}*\s+метр)""")
                .containsMatchIn(priceSuffix) -> "PER_M2"

            Regex("""(?iu)^\s*(?:/|за)\s*(?:1\s*)?сот""").containsMatchIn(priceSuffix) -> "PER_SOTKA"
            transaction == "RENT" -> "MONTH"
            else -> "TOTAL"
        }
        val landArea = decimal("""(\d+(?:[.,]\d+)?)\s*сот""", areaText)
        val totalPrice = priceNormalizer.normalize(ImportedPrice(price, currency, transaction, period, area, landArea))
        if (totalPrice == null) warnings += "Unsupported or invalid sale price"
        return ListingEntity(
            adId = adId(
                row.userId?.toString() ?: senderFromRaw(row.rawMessage) ?: "unknown-${row.chatId}:${row.messageId}",
                location, type, totalPrice, area, landArea
            ),
            chatId = row.chatId,
            messageId = row.messageId,
            messageThreadId = row.messageThreadId,
            sourceRevision = sha256("${row.id}:${row.revision}"),
            title = clean?.title.orEmpty(),
            description = clean?.additionalParameters?.joinToString("\n").orEmpty(),
            rawText = row.rawText,
            location = location,
            address = clean?.address,
            typeOfRealty = type,
            tags = Json.encodeToString(clean?.hashtags.orEmpty()),
            primeParams = buildJsonObject {
                put("details", JsonArray(clean?.keyParameters.orEmpty().map(::JsonPrimitive)))
            }.toString(),
            secondaryParams = buildJsonObject {
                clean?.registration?.let { put("registration", it) }
                if (hasBargain(row.rawText)) put("bargain", "Торг")
            }.toString(),
            governmentPrograms = Json.encodeToString(programs.toList()),
            googleDriveUrls = Json.encodeToString(links),
            price = totalPrice,
            currency = "USD",
            areaM2 = area,
            rooms = rooms,
            floor = floor?.groupValues?.get(1)?.toIntOrNull(),
            totalFloors = floor?.groupValues?.get(2)?.toIntOrNull(),
            landAreaSotka = landArea,
            status = if (warnings.isEmpty()) "ACTIVE" else "NEEDS_REVIEW",
            sourceCreatedAt = maxOf(row.sourceCreatedAt, row.sourceEditedAt),
            createdAt = now,
            updatedAt = now,
            publishedAt = now.takeIf { warnings.isEmpty() })
    }

    private fun parseMoney(raw: String): Long? = runCatching {
        var normalized = raw.trim().replace(Regex("[\\s\\u00a0']"), "")
        normalized = if (Regex("\\d{1,3}([,.]\\d{3})+").matches(normalized)) normalized.replace(Regex("[,.]"), "")
        else normalized.replace(',', '.')
        BigDecimal(normalized).setScale(0, RoundingMode.HALF_UP).longValueExact()
    }.getOrNull()

    private fun extractArea(text: String): Double? {
        val labeled = labeledArea.find(text)?.groupValues?.get(1)?.toArea()
        if (labeled != null) return labeled

        return areaWithUnit.findAll(text).firstNotNullOfOrNull { match ->
            val lineStart = text.lastIndexOf('\n', match.range.first).let { if (it < 0) 0 else it + 1 }
            val before = text.substring(lineStart, match.range.first)
            if (currencyBeforeArea.containsMatchIn(before)) null else match.groupValues[1].toArea()
        }
    }

    private fun String.toArea(): Double? = replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }

    private fun extractPrice(text: String): PriceMatch? {
        val candidates = text.lineSequence().mapIndexedNotNull { lineIndex, line ->
            if (discountOnlyLine.containsMatchIn(line) || ancillaryPriceLine.containsMatchIn(line)) {
                return@mapIndexedNotNull null
            }
            val amounts = moneyWithCurrency.findAll(line).mapNotNull { match ->
                val amount = parseMoney(match.groupValues[1]) ?: return@mapNotNull null
                PriceMatch(
                    amount = amount,
                    currency = match.groupValues[2],
                    line = line,
                    suffix = line.substring(match.range.last + 1),
                    priority = when {
                        preferredPriceLabel.containsMatchIn(line) -> 3
                        priceLabel.containsMatchIn(line) -> 2
                        barePriceLine.containsMatchIn(line) -> 1
                        else -> 0
                    },
                    lineIndex = lineIndex,
                )
            }.toList()
            // The property price is normally the largest currency amount on a line;
            // smaller values in parentheses are commissions and assignment fees.
            amounts.maxByOrNull { it.amount }
        }.filter { it.priority > 0 }.toList()

        return candidates.maxWithOrNull(compareBy<PriceMatch> { it.priority }.thenBy { it.lineIndex })
    }

    private fun extractGovernmentPrograms(text: String): Set<String> {
        val explicitPositive = linkedSetOf<String>()
        val explicitNegative = linkedSetOf<String>()
        var allProgramsExplicitlyEnabled = false
        var allProgramsExplicitlyDisabled = false

        text.lineSequence().forEach { line ->
            val negative = negativeClause.containsMatchIn(line)
            if (genericProgramsLine.containsMatchIn(line)) {
                if (negative) allProgramsExplicitlyDisabled = true
                else if (positiveProgramsClause.containsMatchIn(line)) allProgramsExplicitlyEnabled = true
            }
            programPatterns.forEach { (code, pattern) ->
                if (pattern.containsMatchIn(line)) {
                    if (negative) explicitNegative += code else explicitPositive += code
                }
            }
        }

        val selected = when {
            allProgramsExplicitlyDisabled -> linkedSetOf()
            allProgramsExplicitlyEnabled -> linkedSetOf<String>().apply { addAll(ALL_PROGRAMS) }
            explicitPositive.isNotEmpty() -> explicitPositive
            else -> linkedSetOf<String>().apply { addAll(ALL_PROGRAMS) }
        }
        selected.removeAll(explicitNegative)
        return selected
    }

    private fun hasBargain(text: String): Boolean = text.lineSequence().any { line ->
        bargain.containsMatchIn(line) && !noBargain.containsMatchIn(line)
    }

    private data class PriceMatch(
        val amount: Long,
        val currency: String,
        val line: String,
        val suffix: String,
        val priority: Int,
        val lineIndex: Int,
    )

    private companion object {
        val ALL_PROGRAMS = CatalogCodes.programs.toList()
        val moneyWithCurrency = Regex(
            """(?iu)(?<![\d.,])(?:від\s*)?(\d{1,3}(?:(?:[\s\u00a0']|[.,])\d{3})+(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?)\s*(USD|UAH|EUR|\$|€|₴|грн|долар\p{L}*|євро)"""
        )
        val preferredPriceLabel = Regex("""(?iu)(?:нова|новая|акційна|акционная)\s+(?:ціна|цена|вартість|стоимость)""")
        val priceLabel = Regex("(?iu)(?:ціна|цена|вартість|стоимость)")
        val barePriceLine =
            Regex("""(?iu)^\s*(?:[-–—•*]\s*)?(?:від\s*)?\d[\d\s\u00a0.,']*\s*(?:USD|UAH|EUR|\$|€|₴|грн|долар\p{L}*|євро)""")
        val discountOnlyLine = Regex("""(?iu)знижен\p{L}*\s+(?:ціни|цены).*-\s*\d""")
        val ancillaryPriceLine = Regex(
            """(?iu)(?:оформлен|переуступ|подат|налог|кот[её]л|кладов|комор|парком|лічильник|счетчик|договір|договор)"""
        )
        val programPatterns = linkedMapOf(
            "EOSELIA" to Regex("""(?iu)[#\s]*[єе]осел\p{L}*|єоселя\s*впо"""),
            "VOUCHER" to Regex("(?iu)ваучер"),
            "CERTIFICATE" to Regex("(?iu)сертиф[іи]кат"),
            "POSTANOVA" to Regex("(?iu)постанов"),
        )
        val genericProgramsLine = Regex("""(?iu)(?:держ(?:авн\p{L}*)?\s*програм\p{L}*|\bпрограм\p{L}*)""")
        val positiveProgramsClause = Regex("""(?iu)(?:\bтак\b|\bда\b|всі|усі|все|підход|розгляда|обговорю)""")
        val negativeClause = Regex("""(?iu)(?:\bне\b|без|\bні\b|\bнет\b|не\s+підход|не\s+розгляда)""")
        val bargain = Regex("""(?iu)торг\p{L}*""")
        val noBargain = Regex("""(?iu)(?:без\s+торг\p{L}*|торг\p{L}*\s*(?:не|ні|нет))""")
        val labeledArea = Regex("""(?iu)(?:площа|площадь)\s*[:\-]?\s*(\d+(?:[.,]\d+)?)""")
        val areaWithUnit = Regex("""(?iu)(?<![\d.,])(\d+(?:[.,]\d+)?)\s*(?:м[²2]|м\.?\s*кв\.?|кв\.?\s*м)(?!\p{L})""")
        val currencyBeforeArea = Regex("""(?iu)(?:[$€₴]|\b(?:usd|uah|eur|грн|долар\p{L}*|євро))\s*$""")
    }
}
