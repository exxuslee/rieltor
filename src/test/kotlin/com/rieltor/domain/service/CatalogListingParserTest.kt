package com.rieltor.domain.service

import com.rieltor.infrastructure.database.model.IncomingEntity
import kotlin.test.Test
import kotlin.test.assertEquals

class CatalogListingParserTest {
    @Test fun `extracts exact price and programs without inventing eligibility`() {
        val text = "Ірпінь\n2 кімнатна квартира\nЦіна: 82 000,50 USD\nПлоща: 64 м²\nєОселя\nСертифікат не розглядаємо\nhttps://drive.google.com/drive/folders/example"
        val row = IncomingEntity(chatId = -1, messageId = 1, messageThreadId = 2, groupKey = "g",
            rawMessage = text, rawText = text, sourceCreatedAt = 0, receivedAt = 0, contentHash = "h", verifyAfter = 0)
        val result = CatalogListingParser().parse(listOf(row), "APARTMENT 2", 1)
        assertEquals(82_001, result.price)
        assertEquals("ACTIVE", result.status)
        assertEquals("[\"EOSELIA\"]", result.governmentPrograms)
        assertEquals(64.0, result.areaM2)
        assertEquals("NEEDS_REVIEW", CatalogListingParser().parse(listOf(row), null, 1).status)
    }

    @Test fun `takes apartment room count from configured topic type`() {
        val text = "Ірпінь\nКвартира з ремонтом\nЦіна: 70 000 USD\nПлоща: 48 м²\nhttps://drive.google.com/drive/folders/example"
        val row = IncomingEntity(chatId = -1, messageId = 2, messageThreadId = 3, groupKey = "rooms",
            rawMessage = text, rawText = text, sourceCreatedAt = 0, receivedAt = 0, contentHash = "h", verifyAfter = 0)

        val result = CatalogListingParser().parse(listOf(row), "APARTMENT 3+", 1)

        assertEquals(3, result.rooms)
        assertEquals("ACTIVE", result.status)
    }
}
