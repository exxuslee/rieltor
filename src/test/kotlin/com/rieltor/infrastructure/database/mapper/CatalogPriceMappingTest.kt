package com.rieltor.infrastructure.database.mapper

import com.rieltor.domain.model.ImportedPrice
import com.rieltor.domain.model.ListingStatus
import com.rieltor.domain.usecase.NormalizeCatalogPriceUseCase
import com.rieltor.domain.usecase.PrepareCatalogListingUseCase
import com.rieltor.infrastructure.database.model.IncomingEntity
import kotlin.test.Test
import kotlin.test.assertEquals

class CatalogPriceMappingTest {
    private fun parse(price: String, area: String = "Площа: 50,5 м²", normalizer: NormalizeCatalogPriceUseCase = NormalizeCatalogPriceUseCase()) = CatalogListingMapper(PrepareCatalogListingUseCase(priceNormalizer = normalizer)).fromIncoming(
        IncomingEntity(chatId = -1, messageId = 1, messageThreadId = 2, rawMessage = "original",
            rawText = "Ірпінь\nКвартира\n$price\n$area\nhttps://drive.google.com/drive/folders/example",
            sourceCreatedAt = 0, receivedAt = 0, contentHash = "h", verifyAfter = 0),
        "APARTMENT", 1
    )

    @Test fun `converts currency and area then rounds once to whole dollars`() {
        assertEquals(1, parse("Ціна: 1 грн за 1 м²").price)
        assertEquals("USD", parse("Ціна: 1 EUR").currency)
        assertEquals(1, parse("Ціна: 1 грн за 1 м²", normalizer = NormalizeCatalogPriceUseCase(uahPerUsd = 40.0)).price)
        assertEquals(1, parse("Ціна: 1 EUR", normalizer = NormalizeCatalogPriceUseCase(usdPerEur = 1.25)).price)
    }

    @Test fun `rejects rent unknown currency and invalid quantities`() {
        val normalizer = NormalizeCatalogPriceUseCase()
        listOf(
            ImportedPrice(100, "USD", transactionType = "RENT"),
            ImportedPrice(100, "USD", period = "MONTH"),
            ImportedPrice(100, "GBP"), ImportedPrice(-1, "USD"), ImportedPrice(null, "USD"),
            ImportedPrice(100, "USD", period = "PER_M2", areaM2 = Double.NaN),
            ImportedPrice(Long.MAX_VALUE, "USD", period = "PER_M2", areaM2 = 2.0),
        ).forEach { kotlin.test.assertNull(normalizer(it)) }
        assertEquals(ListingStatus.NeedsReview, parse("Оренда. Ціна: 100 USD").status)
    }

    @Test fun `recognizes unit prices after Ukrainian and Russian price labels`() {
        listOf("Ціна: 1000 USD / м²", "Цена: 1000 USD за 1 м2", "Вартість: 1000 USD за кв. м").forEach {
            assertEquals(50_500, parse(it).price, it)
        }
        val land = parse("Ціна: 1000 USD за 1 сотку", "Ділянка: 6,5 соток")
        assertEquals(6_500, land.price)
        assertEquals(ListingStatus.NeedsReview, parse("Ціна: 1000 USD /м²", "").status)
        assertEquals(1, parse("Ціна: 1 EUR", normalizer = NormalizeCatalogPriceUseCase(usdPerEur = 1.005)).price)
    }
}
