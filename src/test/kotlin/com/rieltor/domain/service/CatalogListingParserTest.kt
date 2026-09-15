package com.rieltor.domain.service

import com.rieltor.infrastructure.database.model.IncomingEntity
import kotlin.test.Test
import kotlin.test.assertEquals

class CatalogListingParserTest {
    @Test fun `extracts exact price and programs without inventing eligibility`() {
        val text = "Ірпінь\n2 кімнатна квартира\nЦіна: 82 000,50 USD\nПлоща: 64 м²\nєОселя\nСертифікат не розглядаємо\nhttps://drive.google.com/drive/folders/example"
        val row = IncomingEntity(chatId = -1, messageId = 1, messageThreadId = 2, groupKey = "g", originalRawMessage = text,
            rawMessage = text, rawText = text, sourceCreatedAt = 0, receivedAt = 0, updatedAt = 0, contentHash = "h", stableSince = 0, verifyAfter = 0)
        val result = CatalogListingParser().parse(listOf(row), "APARTMENT", 1)
        assertEquals(8_200_050, result.price)
        assertEquals("ACTIVE", result.status)
        assertEquals("[\"EOSELIA\"]", result.governmentPrograms)
        assertEquals(64.0, result.areaM2)
        assertEquals("NEEDS_REVIEW", CatalogListingParser().parse(listOf(row), null, 1).status)
    }
}
