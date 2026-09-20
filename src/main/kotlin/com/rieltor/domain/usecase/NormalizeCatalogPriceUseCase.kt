package com.rieltor.domain.usecase

import com.rieltor.domain.model.ImportedPrice

import java.math.BigDecimal
import java.math.RoundingMode

/** Converts a sale price to whole USD for the entire property before persistence. */
class NormalizeCatalogPriceUseCase(
    private val uahPerUsd: Double = 45.0,
    private val usdPerEur: Double = 1.1
) {

    operator fun invoke(source: ImportedPrice): Long? = runCatching {
        require(source.transactionType == "SALE") { "Only sale listings are supported" }
        val amount = requireNotNull(source.amount)
        require(amount > 0) { "Price must be positive" }
        val units = when (source.period) {
            "TOTAL" -> BigDecimal.ONE
            "PER_M2" -> quantity(source.areaM2)
            "PER_SOTKA" -> quantity(source.landAreaSotka)
            else -> error("Unsupported price period")
        }
        val total = BigDecimal(amount).multiply(units)
        val converted = when (source.currency) {
            "USD" -> total
            "UAH" -> total.divide(quantity(uahPerUsd), 0, RoundingMode.HALF_UP)
            "EUR" -> total.multiply(quantity(usdPerEur))
            else -> error("Unsupported currency")
        }.setScale(0, RoundingMode.HALF_UP).longValueExact()
        require(converted > 0) { "Normalized price must be positive" }
        converted
    }.getOrNull()

    private fun quantity(value: Double?): BigDecimal {
        require(value != null && value.isFinite() && value > 0) { "Missing or invalid area / exchange rate" }
        return BigDecimal.valueOf(value)
    }
}
