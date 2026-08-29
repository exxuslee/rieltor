package com.rieltor.domain.service

import com.rieltor.domain.model.TelegramRepostKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TelegramListingIdentityExtractorTest {
    private val extractor = TelegramListingIdentityExtractor()

    @Test
    fun `normalizes equivalent price and street spelling`() {
        val first = extractor.extract(
            5242880,
            "Вул. Мечнікова, буд. 10\nЦіна: 175 000 ${'$'}",
        )
        val repeated = extractor.extract(
            5242880,
            "вулиця Мечнікова 10\nЦіна 175000 USD",
        )

        assertEquals(TelegramRepostKey(5242880, "175000:USD", "мечнікова 10"), first)
        assertEquals(first, repeated)
    }

    @Test
    fun `extracts a street from a labeled address`() {
        val key = extractor.extract(
            77,
            "Адреса: Ірпінь, вул. Університетська 3/1\nВартість — 82 000 €",
        )

        assertEquals(TelegramRepostKey(77, "82000:EUR", "університетська 3 1"), key)
    }

    @Test
    fun `extracts an implicit capitalized address from listing title`() {
        val key = extractor.extract(
            88,
            "Дуплекс в Бучі з ремонтом Києво-Мироцька 88\nЦіна 170000${'$'}",
        )

        assertEquals(TelegramRepostKey(88, "170000:USD", "києво мироцька 88"), key)
    }

    @Test
    fun `does not create a cross-message key when price or address is absent`() {
        assertNull(extractor.extract(1, "Квартира в Ірпені\nЦіна 50000${'$'}"))
        assertNull(extractor.extract(1, "Квартира\nВул. Мечнікова 10"))
    }
}
