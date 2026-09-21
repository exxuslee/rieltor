package com.rieltor.domain.usecase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExtractListingPriceUseCaseTest {
    private val extract = ExtractListingPriceUseCase()

    @Test fun `keeps property price before inline costs`() {
        listOf(
            "Ціна 76500$, (5%/2) оф 12%",
            "Ціна 76500$, оформлення 2%",
            "Ціна 76500$ + переуступка 1000$",
            "Ціна 76500$ (комісія 4000$)",
        ).forEach { assertEquals(76_500L, extract(it)?.amount, it) }
    }

    @Test fun `new price wins even on the same line`() {
        assertEquals(79_000L, extract("Ціна 80000$, нова ціна 79000$")?.amount)
    }

    @Test fun `ancillary costs and discounts are not property prices`() {
        listOf("Ціна комори 3000$", "Котел 690€", "Зниження ціни -500$")
            .forEach { assertNull(extract(it), it) }
    }
}
