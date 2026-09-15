package com.rieltor.domain.service

import com.rieltor.domain.model.CatalogCodes
import com.rieltor.domain.model.sha256
import com.rieltor.infrastructure.database.model.IncomingEntity
import com.rieltor.infrastructure.database.model.ListingEntity
import kotlinx.serialization.json.*
import java.math.BigDecimal

class CatalogListingParser(private val formatter: ListingCaptionFormatter = ListingCaptionFormatter()) {
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
        var programsKnown = false
        val programPatterns = mapOf("EOSELIA" to "[єе]осел[яію]", "VOUCHER" to "ваучер", "CERTIFICATE" to "сертиф[іи]кат", "POSTANOVA" to "постанов")
        text.lineSequence().forEach { line ->
            programPatterns.forEach { (code, pattern) ->
                if (Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(line)) {
                    programsKnown = true
                    // A negative clause never becomes an asserted eligibility claim.
                    if (!Regex("(?iu)(?:\\bне\\b|без|ні\\b|нет\\b|не підход|не розгляда)").containsMatchIn(line)) programs += code
                }
            }
        }
        fun decimal(pattern: String): Double? = Regex(pattern, RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
        val area = decimal("""(?:площа|площадь)\s*[:\-]?\s*(\d+(?:[.,]\d+)?)""")
        val rooms = decimal("""(\d+)\s*[- ]?(?:кімнат|комнат)""")?.toInt()
        val floor = Regex("""(?iu)(?:поверх|этаж)\s*[:\-]?\s*(\d+)\s*(?:/|із|з|из)\s*(\d+)""").find(text)
        val transaction = if (Regex("(?iu)оренд|аренд").containsMatchIn(text)) "RENT" else "SALE"
        val period = when {
            Regex("(?iu)(?:/|за\\s*)м[²2]").containsMatchIn(priceMatch?.value.orEmpty() + text.lineSequence().firstOrNull { it.contains("ціна", true) }.orEmpty()) -> "PER_M2"
            Regex("(?iu)(?:/|за\\s*)сот").containsMatchIn(text) -> "PER_SOTKA"
            transaction == "RENT" -> "MONTH"
            else -> "TOTAL"
        }
        return ListingEntity(groupKey = first.groupKey, chatId = first.chatId, messageId = first.messageId,
            messageThreadId = first.messageThreadId, mediaAlbumId = first.mediaAlbumId,
            rawMessage = Json.encodeToString(rows.map { it.rawMessage }), sourceRevision = sha256(rows.joinToString { "${it.id}:${it.revision}" }),
            title = clean?.title.orEmpty(), description = clean?.additionalParameters?.joinToString("\n").orEmpty(),
            location = location, address = clean?.address, typeOfRealty = type, transactionType = transaction,
            tags = Json.encodeToString(clean?.hashtags.orEmpty()),
            primeParams = buildJsonObject { put("details", JsonArray(clean?.keyParameters.orEmpty().map(::JsonPrimitive))) }.toString(),
            secondaryParams = buildJsonObject { clean?.registration?.let { put("registration", it) } }.toString(),
            governmentPrograms = Json.encodeToString(programs.toList()), governmentProgramsKnown = programsKnown,
            googleDriveUrl = links.firstOrNull(), googleDriveUrls = Json.encodeToString(links), price = price, currency = currency,
            pricePeriod = period, areaM2 = area, rooms = rooms, floor = floor?.groupValues?.get(1)?.toIntOrNull(),
            totalFloors = floor?.groupValues?.get(2)?.toIntOrNull(),
            landAreaSotka = decimal("""(\d+(?:[.,]\d+)?)\s*сот"""),
            status = if (warnings.isEmpty()) "ACTIVE" else "NEEDS_REVIEW",
            sourceCreatedAt = first.sourceCreatedAt, receivedAt = first.receivedAt, cdt = now, updatedAt = now,
            publishedAt = now.takeIf { warnings.isEmpty() }, parseWarnings = Json.encodeToString(warnings))
    }

    private fun parseMoney(raw: String): Long? = runCatching {
        var normalized = raw.trim().replace(Regex("[\\s\\u00a0']"), "")
        normalized = if (Regex("\\d{1,3}([,.]\\d{3})+").matches(normalized)) normalized.replace(Regex("[,.]"), "")
        else normalized.replace(',', '.')
        BigDecimal(normalized).movePointRight(2).longValueExact()
    }.getOrNull()
}
