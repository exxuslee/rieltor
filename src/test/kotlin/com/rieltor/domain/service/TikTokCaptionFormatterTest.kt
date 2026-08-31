package com.rieltor.domain.service

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ListingCaptionFormatterTest {
    private val filter = ListingCaptionFormatter()

    @Test
    fun `removes private contacts drive links commission and registration percentage`() {
        val source = """
            Ірпінь
            Таунхаус з ремонтом
            Вул Мечнікова
            Три кімнати
            84 кв.м
            ГАЗ
            Ціна. 175000${'$'} (5000${'$'}/2)
            Комісія 5%\2
            Оформлення 2%
            0990852854 Олексій Новатор
            093 036 30 46 Максим АН НОВАТОР
            https://drive.google.com/drive/folders/example
        """.trimIndent()

        val result = requireNotNull(filter.filter(source))

        assertEquals(
            """🏠 Таунхаус з ремонтом — Ірпінь

• Вул Мечнікова
• Три кімнати
• 84 кв.м
• ГАЗ
• Ціна. 175000${'$'}

🤙 066-372-71-02 Ірина

#нерухомість #продажнерухомості #таунхаус #Ірпінь #ІринаЛіннік""",
            result,
        )
    }

    @Test
    fun `keeps details in a separate description and adds relevant hashtags`() {
        val source = """
            Дуплекс в Бучі з ремонтом Києво-Мироцька 88
            Площа дуплекса: 93 м2
            Земельна ділянка: 2.5 сотки
            Тепла підлога всюди окрім кімнат
            не введений в експлуатацію
            Ціна: 170 000${'$'} (5000${'$'}/2)
            Комісія: 5000${'$'}
            +380635823820 Вячеслав
            https://docs.google.com/document/d/example/edit
        """.trimIndent()

        val result = requireNotNull(filter.filter(source))

        assertContains(result, "• Площа дуплекса: 93 м2")
        assertContains(result, "не введений в експлуатацію")
        assertContains(result, "#дуплекс #Буча")
        assertFalse(result.contains("5000${'$'}/2"))
        assertFalse(result.contains("Вячеслав"))
        assertFalse(result.contains("google.com"))
    }

    @Test
    fun `returns null for an empty caption`() {
        assertEquals(null, filter.filter(null))
        assertEquals(null, filter.filter("  \n "))
    }

    @Test
    fun `removes abbreviated inline commission and a contact name on the next line`() {
        val source = """
            Таунхауси в Михайлівці-Рубежівці
            Площа 44 м2
            Ціна 45000${'$'} ком 5%/2
            0968383876
            Сергій
            АН «Novator»
        """.trimIndent()

        val result = requireNotNull(filter.filter(source))

        assertContains(result, "• Ціна 45000${'$'}")
        assertFalse(result.contains("5%/2"))
        assertFalse(result.contains("Сергій"))
        assertFalse(result.contains("Novator", ignoreCase = true))
        assertContains(result, "🤙 066-372-71-02 Ірина")
    }

    @Test
    fun `removes any parenthesized note immediately after a price`() {
        val source = """
            Квартира в Ірпені
            Ціна 22 000${'$'} ( 2000/2)
        """.trimIndent()

        val result = requireNotNull(filter.filter(source))

        assertContains(result, "Ціна 22 000${'$'}")
        assertFalse(result.contains("( 2000/2)"))
    }

    @Test
    fun `extracts title price and public contact for the first photo`() {
        val caption = requireNotNull(filter.filter("Квартира в Ірпені\nЦіна 22 000${'$'}"))

        val overlay = requireNotNull(filter.photoOverlay(caption))

        assertEquals("Квартира в Ірпені", overlay.title)
        assertEquals("Ціна 22 000${'$'}", overlay.price)
        assertEquals("066-372-71-02 Ірина", overlay.contact)
    }
}
