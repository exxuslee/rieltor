package com.rieltor.domain.usecase

import com.rieltor.domain.model.CatalogCodes
import com.rieltor.domain.model.ImportedPrice
import com.rieltor.domain.model.ListingImportSource
import com.rieltor.domain.model.PreparedCatalogListing

class PrepareCatalogListingUseCase(
    private val prepareContent: PrepareListingContentUseCase = PrepareListingContentUseCase(),
    private val extractPrice: ExtractListingPriceUseCase = ExtractListingPriceUseCase(),
    private val priceNormalizer: NormalizeCatalogPriceUseCase = NormalizeCatalogPriceUseCase()
) {
    operator fun invoke(source: ListingImportSource): PreparedCatalogListing {
        val type = source.typeOfRealty
        val clean = prepareContent(source.text)
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
            .filter { Regex(it.second, RegexOption.IGNORE_CASE).containsMatchIn(source.text) }.map { it.first }
        val location = when (matchedLocations.size) {
            0 -> "OTHER"
            1 -> matchedLocations.single()
            else -> null
        }
        val priceMatch = extractPrice(source.text)
        val price = priceMatch?.amount
        val currency = priceMatch?.currency?.lowercase()?.let {
            when {
                it in setOf("usd", "$") || it.startsWith("долар") -> "USD"
                it in setOf("uah", "₴", "грн") -> "UAH"; else -> "EUR"
            }
        }
        if (price == null || price <= 0) warnings += "Missing or ambiguous price/currency"
        if (type !in CatalogCodes.types) warnings += "Missing or unsupported property type"
        if (location == null) warnings += "Ambiguous location"
        if (source.photoLinks.isEmpty() && !source.hasAttachedPhotos) warnings += "Missing listing photos"
        if (clean == null) warnings += "Missing public content"
        val programs = extractGovernmentPrograms(source.text)
        fun decimal(pattern: String, text: String = source.text): Double? =
            Regex(pattern, RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.replace(',', '.')
                ?.toDoubleOrNull()

        val areaText = source.text.lineSequence().filterNot { it == priceMatch?.line }.joinToString("\n")
        val area = extractArea(areaText)
        val rooms = CatalogCodes.apartmentRooms(type)
            ?: decimal("""(\d+)\s*[- ]?(?:кімнат|комнат)""")?.toInt()
        val floor = Regex("""(?iu)(?:поверх|этаж)\s*[:\-]?\s*(\d+)\s*(?:/|із|з|из)\s*(\d+)""").find(source.text)
        val transaction = if (Regex("(?iu)оренд|аренд").containsMatchIn(source.text)) "RENT" else "SALE"
        val priceSuffix = priceMatch?.suffix.orEmpty()
        val period = when {
            Regex("""(?iu)^\s*(?:/|за)\s*(?:1\s*)?(?:м[²2]|кв\.?\s*м|квадратн\p{L}*\s+метр)""")
                .containsMatchIn(priceSuffix) -> "PER_M2"

            Regex("""(?iu)^\s*(?:/|за)\s*(?:1\s*)?сот""").containsMatchIn(priceSuffix) -> "PER_SOTKA"
            transaction == "RENT" -> "MONTH"
            else -> "TOTAL"
        }
        val landArea = decimal("""(\d+(?:[.,]\d+)?)\s*сот""", areaText)
        val totalPrice = priceNormalizer(ImportedPrice(price, currency, transaction, period, area, landArea))
        if (totalPrice == null) warnings += "Unsupported or invalid sale price"
        return PreparedCatalogListing(
            content = clean, location = location, typeOfRealty = type, price = totalPrice,
            areaM2 = area, landAreaSotka = landArea, rooms = rooms,
            floor = floor?.groupValues?.get(1)?.toIntOrNull(),
            totalFloors = floor?.groupValues?.get(2)?.toIntOrNull(),
            governmentPrograms = programs, hasBargain = hasBargain(source.text),
            photoLinks = source.photoLinks, warnings = warnings.toList(),
        )
    }

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

    private companion object {
        val ALL_PROGRAMS = CatalogCodes.programs.toList()
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
