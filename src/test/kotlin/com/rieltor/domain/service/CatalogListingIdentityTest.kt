package com.rieltor.domain.service
import com.rieltor.domain.model.adId
import com.rieltor.infrastructure.database.mapper.CatalogListingMapper
import com.rieltor.infrastructure.database.model.IncomingEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CatalogListingIdentityTest {
    private fun identity(text: String, sender: Long? = 123, message: Long = 1): String =
        CatalogListingMapper().fromIncoming(IncomingEntity(
            chatId = -100, messageId = message, messageThreadId = 1, userId = sender,
            rawText = text, sourceCreatedAt = 1000, contentHash = "hash", verifyAfter = 0,
        ), "APARTMENT 1", 2000).adId
    @Test fun `equivalent price and area spelling produces the same identity`() {
        val first = identity("Ірпінь\nПлоща: 40 м²\nЦіна: 175 000 USD")
        val repeated = identity("Ірпінь\nПлоща: 40,0 м²\nЦіна 175000 $", message = 2)
        assertEquals(adId("123", "IRPIN", "APARTMENT 1", 175000, 40.0, null), first)
        assertEquals(first, repeated)
    }
    @Test fun `different senders and prices are distinct listings`() {
        val text = "Ірпінь\nЦіна: 80000 USD"
        assertNotEquals(identity(text), identity(text, sender = 456))
        assertNotEquals(identity(text), identity(text.replace("80000", "79000")))
    }
    @Test fun `unknown sender identity is scoped to the source message`() {
        val text = "Ірпінь\nЦіна: 80000 USD"
        assertNotEquals(identity(text, sender = null), identity(text, sender = null, message = 2))
    }
}
