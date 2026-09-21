package com.rieltor.domain.usecase

import com.rieltor.domain.model.ListingImportSource
import kotlin.test.*

/** Reduced examples from the two supplied message exports, without source contacts. */
class SuppliedListingExamplesTest {
    private val prepare = PrepareCatalogListingUseCase()
    private fun parse(text: String, type: String = "HOUSE") =
        prepare(ListingImportSource(text, type, hasAttachedPhotos = true))

    @Test fun `Kyiv apartment has worded area single floor and inflected rooms`() {
        val result = parse("Київ\nЖК Комфорт Таун\n85 квадратів\n5 поверх\n3-х кімнатна квартира з ремонтом\nВартість 175000$\nПодатки 12% (продавець сплачує свої)\nКомісія 3%/2", "APARTMENT")
        assertEquals(85.0, result.areaM2)
        assertEquals(5, result.floor)
        assertNull(result.totalFloors)
        assertEquals(3, result.rooms)
        assertEquals(175000L, result.price)
        val content = assertNotNull(result.content)
        assertFalse((content.keyParameters + content.additionalParameters).any { it.contains("Податки") })
    }

    @Test fun `distance to Irpin does not locate the house in Irpin`() {
        val result = parse("Село Соснівка\n40км від Ірпеня\nБудинок\nЗагальна площа 72м2\n4 кімнати\nПлоща ділянки 25соток\nЦіна 20000$ (2000/2)")
        assertEquals("OTHER", result.location)
        assertEquals("Будинок — Село Соснівка", assertNotNull(result.content).title)
        assertFalse(assertNotNull(result.content).hashtags.contains("#Ірпінь"))
        assertEquals(72.0, result.areaM2)
        assertEquals(25.0, result.landAreaSotka)
        assertEquals(4, result.rooms)
        assertTrue(result.readyForPublication)
        assertEquals("OTHER", parse("Гаврилівка (10 км від Гостомеля)\nБудинок\nЦіна 20000$").location)
        assertEquals("BUCHA", parse("Буча\nБудинок\n18 км від Ірпеня\nЦіна 20000$").location)
    }

    @Test fun `land area is not a building area`() {
        val result = parse("Ділянка\nЗагальна площа: 22 сотки\nЦіна 55000$", "LAND")
        assertNull(result.areaM2)
        assertEquals(22.0, result.landAreaSotka)
        assertEquals(120.0, parse("Будинок\nЗагальна площа: 22 сотки\nПлоща будинку — 120 м2\nЦіна 55000$").areaM2)
    }

    @Test fun `thousands and abbreviated dollars agree in catalog and caption`() {
        mapOf("Вартість 77 тис$" to 77000L, "Вартість 70 тис$" to 70000L,
            "Ціна 80000 дол" to 80000L, "Ціна 69000 дол." to 69000L,
            "Ціна 77,5 тис$" to 77500L).forEach { (price, amount) ->
            val result = parse("Будинок\n$price")
            assertEquals(amount, result.price, price)
            assertTrue(result.readyForPublication, price)
            assertEquals(price.substringAfter(' '), assertNotNull(result.content).price)
        }
    }

    @Test fun `discount never overrides actual bare price`() {
        val result = parse("Будинок\nЗниження ціни на 4000$‼️\n83000$ (4000/2)")
        assertEquals(83000L, result.price)
        assertNull(ExtractListingPriceUseCase()("Зниження ціни на 4000$"))
        assertEquals(320000L, ExtractListingPriceUseCase()("Зниження ціни, стара ціна 365000$ - нова ціна 320000$")?.amount)
    }

    @Test fun `public content excludes bare commission and agency names`() {
        val content = assertNotNull(parse("Квартира\nЦіна 48500$\n2500/2\nАН Avant-Garde R.Е.").content)
        assertTrue(content.additionalParameters.isEmpty())
        assertTrue(content.keyParameters.isEmpty())
    }

    @Test fun `missing currency stays subject to review`() {
        assertFalse(parse("Будинок\nЦіна 60000, (5%/2) оф 2%").readyForPublication)
    }
}
