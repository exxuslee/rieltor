package com.rieltor.domain.service

import com.rieltor.domain.model.CatalogCodes
import com.rieltor.domain.model.sha256
import com.rieltor.infrastructure.database.model.IncomingEntity
import com.rieltor.infrastructure.database.model.ListingEntity
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.math.RoundingMode

class CatalogListingParser(private val formatter: ListingCaptionFormatter = ListingCaptionFormatter(),
    private val priceNormalizer: CatalogPriceNormalizer = CatalogPriceNormalizer()) {
    fun parse(rows: List<IncomingEntity>, type: String?, now: Long): ListingEntity {
        val first = rows.first()
        val text = rows.map { it.rawText }.filter { it.isNotBlank() }.distinct().joinToString("\n")
        val clean = formatter.filter(text)
        val warnings = mutableListOf<String>()
        val location = listOf("IRPIN" to "ірп[іе]н|ирпен", "BUCHA" to "буч[аіи]", "VORZEL" to "ворзел|ворзель", "HOSTOMEL" to "гостомел")
            .filter { Regex(it.second, RegexOption.IGNORE_CASE).containsMatchIn(text) }.map { it.first }.singleOrNull()
        val prices = Regex("""(?iu)(?:ціна|цена|вартість|стоимость)\s*[:.\-]?\s*(?:від\s*)?([\d][\d\s\u00a0.,']*)\s*(USD|UAH|EUR|\$|€|₴|грн|долар\p{L}*|євро)""")
            .findAll(text).toList()
        val priceMatch = prices.singleOrNull()
        val price = priceMatch?.groupValues?.get(1)?.let(::parseMoney)
        val currency = priceMatch?.groupValues?.get(2)?.lowercase()?.let {
            when { it in setOf("usd", "$") || it.startsWith("долар") -> "USD"
                it in setOf("uah", "₴", "грн") -> "UAH"; else -> "EUR" }
        }
        if (price == null || price <= 0) warnings += "Missing or ambiguous price/currency"
        if (type !in CatalogCodes.types) warnings += "Configure topicTypeMapping for ${first.chatId}:${first.messageThreadId}"
        if (location == null) warnings += "Missing or ambiguous location"
        val links = GoogleDriveLinkExtractor().extract(text)
        if (links.isEmpty()) warnings += "Missing Google Drive URL"
        if (clean == null) warnings += "Missing public content"
        val programs = linkedSetOf<String>()
        val programPatterns = mapOf("EOSELIA" to "[єе]осел[яію]", "VOUCHER" to "ваучер", "CERTIFICATE" to "сертиф[іи]кат", "POSTANOVA" to "постанов")
        text.lineSequence().forEach { line ->
            programPatterns.forEach { (code, pattern) ->
                if (Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(line)) {
                    // A negative clause never becomes an asserted eligibility claim.
                    if (!Regex("(?iu)(?:\\bне\\b|без|ні\\b|нет\\b|не підход|не розгляда)").containsMatchIn(line)) programs += code
                }
            }
        }
        fun decimal(pattern: String, source: String = text): Double? = Regex(pattern, RegexOption.IGNORE_CASE).find(source)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
        val areaText = text.lineSequence().filterNot { priceMatch != null && it.contains(priceMatch.value) }.joinToString("\n")
        val area = decimal("""(?:площа|площадь)\s*[:\-]?\s*(\d+(?:[.,]\d+)?)""")
        val rooms = decimal("""(\d+)\s*[- ]?(?:кімнат|комнат)""")?.toInt()
        val floor = Regex("""(?iu)(?:поверх|этаж)\s*[:\-]?\s*(\d+)\s*(?:/|із|з|из)\s*(\d+)""").find(text)
        val transaction = if (Regex("(?iu)оренд|аренд").containsMatchIn(text)) "RENT" else "SALE"
        val priceSuffix = priceMatch?.let { text.substring(it.range.last + 1).lineSequence().first() }.orEmpty()
        val period = when {
            Regex("""(?iu)^\s*(?:/|за)\s*(?:1\s*)?(?:м[²2]|кв\.?\s*м|квадратн\p{L}*\s+метр)""").containsMatchIn(priceSuffix) -> "PER_M2"
            Regex("""(?iu)^\s*(?:/|за)\s*(?:1\s*)?сот""").containsMatchIn(priceSuffix) -> "PER_SOTKA"
            transaction == "RENT" -> "MONTH"
            else -> "TOTAL"
        }
        val landArea = decimal("""(\d+(?:[.,]\d+)?)\s*сот""", areaText)
        val totalPrice = priceNormalizer.normalize(ImportedPrice(price, currency, transaction, period, area, landArea))
        if (totalPrice == null) warnings += "Unsupported or invalid sale price"
        return ListingEntity(groupKey = first.groupKey, chatId = first.chatId, messageId = first.messageId,
            messageThreadId = first.messageThreadId,
            sourceRevision = sha256(rows.joinToString { "${it.id}:${it.revision}" }),
            title = clean?.title.orEmpty(), description = clean?.additionalParameters?.joinToString("\n").orEmpty(),
            location = location, address = clean?.address, typeOfRealty = type,
            tags = Json.encodeToString(clean?.hashtags.orEmpty()),
            primeParams = buildJsonObject { put("details", JsonArray(clean?.keyParameters.orEmpty().map(::JsonPrimitive))) }.toString(),
            secondaryParams = buildJsonObject { clean?.registration?.let { put("registration", it) } }.toString(),
            governmentPrograms = Json.encodeToString(programs.toList()),
            googleDriveUrls = Json.encodeToString(links), price = totalPrice, currency = "USD",
            areaM2 = area, rooms = rooms, floor = floor?.groupValues?.get(1)?.toIntOrNull(),
            totalFloors = floor?.groupValues?.get(2)?.toIntOrNull(),
            landAreaSotka = landArea,
            status = if (warnings.isEmpty()) "ACTIVE" else "NEEDS_REVIEW",
            sourceCreatedAt = rows.maxOf { maxOf(it.sourceCreatedAt, it.sourceEditedAt) }, createdAt = now, updatedAt = now,
            publishedAt = now.takeIf { warnings.isEmpty() })
    }

    private fun parseMoney(raw: String): Long? = runCatching {
        var normalized = raw.trim().replace(Regex("[\\s\\u00a0']"), "")
        normalized = if (Regex("\\d{1,3}([,.]\\d{3})+").matches(normalized)) normalized.replace(Regex("[,.]"), "")
        else normalized.replace(',', '.')
        BigDecimal(normalized).setScale(0, RoundingMode.HALF_UP).longValueExact()
    }.getOrNull()
}
