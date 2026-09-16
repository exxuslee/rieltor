package com.rieltor.domain.service

import com.rieltor.infrastructure.database.model.IncomingEntity
import kotlin.test.Test
import kotlin.test.assertEquals

class CatalogPriceNormalizerTest {
    private fun parse(price: String, area: String = "Площа: 50,5 м²") = CatalogListingParser().parse(listOf(
        IncomingEntity(chatId = -1, messageId = 1, messageThreadId = 2, groupKey = "g",
            originalRawMessage = "original", rawMessage = "original",
            rawText = "Ірпінь\nКвартира\n$price\n$area\nhttps://drive.google.com/drive/folders/example",
            sourceCreatedAt = 0, receivedAt = 0, updatedAt = 0, contentHash = "h", stableSince = 0, verifyAfter = 0)
    ), "APARTMENT", 1)

    @Test fun `converts currency and area then rounds once to cents`() {
        val row = parse("Ціна: 1 грн за 1 м²")
        val result = CatalogPriceNormalizer().normalize(row)
        assertEquals(112, result.price)
        assertEquals("USD", result.currency)
        assertEquals("TOTAL", result.pricePeriod)
        assertEquals(result, CatalogPriceNormalizer().normalize(result))
        assertEquals(126, CatalogPriceNormalizer(uahPerUsd = 40.0).normalize(row).price)
        assertEquals(125, CatalogPriceNormalizer(usdPerEur = 1.25).normalize(parse("Ціна: 1 EUR")).price)
    }

    @Test fun `recognizes unit prices after Ukrainian and Russian price labels`() {
        listOf("Ціна: 1000 USD / м²", "Цена: 1000 USD за 1 м2", "Вартість: 1000 USD за кв. м").forEach {
            assertEquals(5_050_000, CatalogPriceNormalizer().normalize(parse(it)).price, it)
        }
        val land = parse("Ціна: 1000 USD за 1 сотку", "Ділянка: 6,5 соток")
        assertEquals(650_000, CatalogPriceNormalizer().normalize(land).price)
        assertEquals("NEEDS_REVIEW", CatalogPriceNormalizer().normalize(parse("Ціна: 1000 USD /м²", "")).status)
        assertEquals(101, CatalogPriceNormalizer(usdPerEur = 1.005).normalize(parse("Ціна: 1 EUR")).price)
    }
}
