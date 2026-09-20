package com.rieltor.domain.model

/** Source-only price metadata. Never persisted as a catalog listing. */
data class ImportedPrice(
    val amount: Long?, val currency: String?, val transactionType: String = "SALE",
    val period: String = "TOTAL", val areaM2: Double? = null, val landAreaSotka: Double? = null,
)

