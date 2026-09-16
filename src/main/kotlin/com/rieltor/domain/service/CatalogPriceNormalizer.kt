package com.rieltor.domain.service

import com.rieltor.infrastructure.database.model.ListingEntity
import java.math.BigDecimal
import java.math.RoundingMode

/** Prices are persisted as cents of USD for the whole property, rounded once. */
class CatalogPriceNormalizer(private val uahPerUsd: Double = 45.0, private val usdPerEur: Double = 1.1) {
    fun normalize(row: ListingEntity): ListingEntity {
        if (row.price == null) return row
        val cents = runCatching {
            require(row.transactionType == "SALE") { "Only sale listings are supported" }
            require(row.price > 0) { "Price must be positive" }
            val quantity = when (row.pricePeriod) {
                "TOTAL" -> BigDecimal.ONE
                "PER_M2" -> quantity(row.areaM2)
                "PER_SOTKA" -> quantity(row.landAreaSotka)
                else -> error("Unsupported price period")
            }
            val total = BigDecimal(row.price).multiply(quantity)
            val converted = when (row.currency) {
                "USD" -> total
                "UAH" -> total.divide(quantity(uahPerUsd), 0, RoundingMode.HALF_UP)
                "EUR" -> total.multiply(quantity(usdPerEur))
                else -> error("Unsupported currency")
            }.setScale(0, RoundingMode.HALF_UP).longValueExact()
            require(converted > 0) { "Normalized price must be positive" }
            converted
        }.getOrElse {
            return row.copy(status = if (row.status == "ACTIVE") "NEEDS_REVIEW" else row.status)
        }
        return row.copy(price = cents, currency = "USD", pricePeriod = "TOTAL")
    }

    private fun quantity(value: Double?): BigDecimal {
        require(value != null && value.isFinite() && value > 0) { "Missing or invalid area / exchange rate" }
        return BigDecimal.valueOf(value)
    }
}
