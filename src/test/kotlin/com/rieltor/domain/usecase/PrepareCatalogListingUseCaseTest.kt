package com.rieltor.domain.usecase

import com.rieltor.domain.model.ListingImportSource
import kotlin.test.*

class PrepareCatalogListingUseCaseTest {
    private val prepare = PrepareCatalogListingUseCase()

    @Test fun `extracts floor variants from incoming messages`() {
        listOf("37.7м2, 3/6 поверх", "3/6", "Поверх 3/6").forEach { detail ->
            val result = prepare(ListingImportSource("Ірпінь\n1к з ремонтом\n$detail\nЦіна 76500$", "APARTMENT 1"))
            assertEquals(3, result.floor, detail)
            assertEquals(6, result.totalFloors, detail)
        }
    }

    @Test fun `extracts labeled room count from house messages`() {
        val result = prepare(ListingImportSource("Будинок\nПлоща 127 м.кв.\nКімнат - 4\nЦіна 120000$", "HOUSE"))
        assertEquals(4, result.rooms)
        assertEquals(127.0, result.areaM2)
    }

    @Test fun `inline registration does not lose public price`() {
        val result = prepare(ListingImportSource("Ірпінь\n1к з ремонтом\nЦіна 76500$, оформлення 12%", "APARTMENT 1"))
        assertEquals(76_500L, result.price)
        assertEquals("76500$", assertNotNull(result.content).price)
    }

    @Test fun `catalog and public content select the new price and omit obsolete prices`() {
        val text = "Буча\n2к квартира\nЦіна 80 000 USD\nНова ціна 79000 USD\nЦіна комори 3000 USD"
        val result = prepare(ListingImportSource(text, "APARTMENT 2", hasAttachedPhotos = true))
        val content = assertNotNull(result.content)

        assertTrue(result.readyForPublication)
        assertEquals(79_000L, result.price)
        assertEquals("79000 USD", content.price)
        assertTrue((content.keyParameters + content.additionalParameters).none { it.contains("USD") })
    }

    @Test fun `last ordinary price wins in both catalog and public content`() {
        val result = prepare(ListingImportSource(
            "Ірпінь\nКвартира\nЦіна 69000 USD\nЦіна 65000 USD", "APARTMENT 1",
            hasAttachedPhotos = true,
        ))
        assertEquals(65_000L, result.price)
        assertEquals("65000 USD", assertNotNull(result.content).price)
    }

    @Test fun `validation returns reasons without requiring database entities`() {
        val result = prepare(ListingImportSource("Ірпінь\nКвартира\nЦіна 100000 USD", null))
        assertFalse(result.readyForPublication)
        assertContains(result.warnings, "Missing listing photos")
        assertContains(result.warnings, "Missing or unsupported property type")
    }

    @Test fun `normalizes unit price while preserving original public price`() {
        val result = prepare(ListingImportSource(
            "Ірпінь\nКвартира\nЦіна 1000 USD / м²\nПлоща 50 м²", "APARTMENT 1",
            hasAttachedPhotos = true,
        ))
        assertEquals(50_000L, result.price)
        assertTrue(result.readyForPublication)
    }
}
