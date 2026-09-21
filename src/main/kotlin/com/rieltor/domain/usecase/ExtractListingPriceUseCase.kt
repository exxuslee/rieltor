package com.rieltor.domain.usecase

import java.math.BigDecimal
import java.math.RoundingMode

/** Selects the current property price, excluding ancillary costs and discounts. */
class ExtractListingPriceUseCase {
    private fun parseMoney(raw: String, thousands: Boolean): Long? = runCatching {
        var normalized = raw.trim().replace(Regex("[\\s\\u00a0']"), "")
        normalized = if (Regex("\\d{1,3}([,.]\\d{3})+").matches(normalized)) normalized.replace(Regex("[,.]"), "")
        else normalized.replace(',', '.')
        BigDecimal(normalized).multiply(if (thousands) BigDecimal(1000) else BigDecimal.ONE).setScale(0, RoundingMode.HALF_UP).longValueExact()
    }.getOrNull()

    operator fun invoke(text: String): PriceMatch? {
        val candidates = text.lineSequence().mapIndexedNotNull { lineIndex, line ->
            var previousEnd = 0
            val amounts = moneyWithCurrency.findAll(line).mapNotNull { match ->
                val context = line.substring(previousEnd, match.range.first)
                val firstAmount = previousEnd == 0
                previousEnd = match.range.last + 1
                if (discountOnlyLine.containsMatchIn(context + match.value) ||
                    ancillaryPriceLine.containsMatchIn(context)) return@mapNotNull null
                val amount = parseMoney(match.groupValues[1], match.groupValues[2].isNotEmpty()) ?: return@mapNotNull null
                PriceMatch(
                    amount = amount,
                    display = match.value.trim(),
                    currency = match.groupValues[3],
                    line = line,
                    suffix = line.substring(match.range.last + 1),
                    priority = when {
                        preferredPriceLabel.containsMatchIn(context) -> 3
                        priceLabel.containsMatchIn(context) -> 2
                        firstAmount && barePriceLine.containsMatchIn(line) -> 1
                        else -> 0
                    },
                    lineIndex = lineIndex,
                )
            }.toList()
            // Labels belong to individual amounts: a new price can follow the old one.
            amounts.filter { it.priority > 0 }.maxByOrNull { it.priority }
        }.filter { it.priority > 0 }.toList()

        return candidates.maxWithOrNull(compareBy<PriceMatch> { it.priority }.thenBy { it.lineIndex })
    }

    data class PriceMatch(
        val amount: Long,
        val display: String,
        val currency: String,
        val line: String,
        val suffix: String,
        val priority: Int,
        val lineIndex: Int,
    )

    private companion object {
        val moneyWithCurrency = Regex(
            """(?iu)(?<![\d.,])(?:від\s*)?(\d{1,3}(?:(?:[\s\u00a0']|[.,])\d{3})+(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?)\s*(тис\.?|тыс\.?)?\s*(USD|UAH|EUR|\$|€|₴|грн|дол(?:ар\p{L}*|лар\p{L}*)?\.?|євро)(?!\p{L})"""
        )
        val preferredPriceLabel = Regex("""(?iu)(?:нова|новая|акційна|акционная)\s+(?:ціна|цена|вартість|стоимость)""")
        val priceLabel = Regex("(?iu)(?:ціна|цена|вартість|стоимость)")
        val barePriceLine =
            Regex("""(?iu)^\s*(?:[-–—•*]\s*)?(?:від\s*)?\d[\d\s\u00a0.,']*\s*(?:тис\.?|тыс\.?)?\s*(?:USD|UAH|EUR|\$|€|₴|грн|дол(?:ар\p{L}*|лар\p{L}*)?\.?|євро)""")
        val discountOnlyLine = Regex("""(?iu)(?:знижен\p{L}*|зміна|снижен\p{L}*)\s+(?:ціни|цены)\s*(?:на\s*|[-–—]\s*)\d""")
        val ancillaryPriceLine = Regex(
            """(?iu)(?:оформлен|переуступ|подат|налог|кот[её]л|кладов|комор|парком|лічильник|счетчик|договір|договор)"""
        )
    }
}
