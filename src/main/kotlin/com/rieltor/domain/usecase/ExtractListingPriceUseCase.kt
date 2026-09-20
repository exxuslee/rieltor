package com.rieltor.domain.usecase

import java.math.BigDecimal
import java.math.RoundingMode

/** Selects the current property price, excluding ancillary costs and discounts. */
class ExtractListingPriceUseCase {
    private fun parseMoney(raw: String): Long? = runCatching {
        var normalized = raw.trim().replace(Regex("[\\s\\u00a0']"), "")
        normalized = if (Regex("\\d{1,3}([,.]\\d{3})+").matches(normalized)) normalized.replace(Regex("[,.]"), "")
        else normalized.replace(',', '.')
        BigDecimal(normalized).setScale(0, RoundingMode.HALF_UP).longValueExact()
    }.getOrNull()

    operator fun invoke(text: String): PriceMatch? {
        val candidates = text.lineSequence().mapIndexedNotNull { lineIndex, line ->
            if (discountOnlyLine.containsMatchIn(line) || ancillaryPriceLine.containsMatchIn(line)) {
                return@mapIndexedNotNull null
            }
            val amounts = moneyWithCurrency.findAll(line).mapNotNull { match ->
                val amount = parseMoney(match.groupValues[1]) ?: return@mapNotNull null
                PriceMatch(
                    amount = amount,
                    display = match.value.trim(),
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
    }
}
