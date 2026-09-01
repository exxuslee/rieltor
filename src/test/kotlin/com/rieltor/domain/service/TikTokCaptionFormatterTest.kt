package com.rieltor.domain.service

import kotlin.test.*

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

        val listing = requireNotNull(filter.filter(source))
        val result = requireNotNull(filter.forTikTok(listing))

        assertEquals("Таунхаус з ремонтом — Ірпінь", listing.title)
        assertEquals("175000${'$'}", listing.price)
        assertEquals("Вул Мечнікова", listing.address)
        assertEquals(listOf("Три кімнати", "84 кв.м", "ГАЗ"), listing.keyParameters)
        assertEquals(emptyList(), listing.additionalParameters)
        assertEquals("066-372-71-02", listing.phone)
        assertEquals(5, listing.hashtags.size)
        assertContains(result, "📍 Вул Мечнікова")
        assertContains(result, "💰 175000${'$'}")
        assertContains(result, "🤙 066-372-71-02 Ірина")
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

        val listing = requireNotNull(filter.filter(source))
        val result = requireNotNull(filter.forTikTok(listing))

        assertContains(result, "• Площа дуплекса: 93 м2")
        assertContains(result, "не введений в експлуатацію")
        assertEquals(5, listing.hashtags.size)
        assertContains(result, "#дуплекс #Буча")
        assertFalse(result.contains("5000${'$'}/2"))
        assertFalse(result.contains("Вячеслав"))
        assertFalse(result.contains("google.com"))
    }

    @Test
    fun `returns null for an empty caption`() {
        assertNull(filter.filter(null))
        assertNull(filter.filter("  \n "))
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

        val listing = requireNotNull(filter.filter(source))
        val result = requireNotNull(filter.forTikTok(listing))

        assertEquals("45000${'$'}", listing.price)
        assertContains(result, "💰 45000${'$'}")
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

        val listing = requireNotNull(filter.filter(source))
        val result = requireNotNull(filter.forTikTok(listing))

        assertEquals("22 000${'$'}", listing.price)
        assertContains(result, "💰 22 000${'$'}")
        assertFalse(result.contains("( 2000/2)"))
    }

    @Test
    fun `extracts title price and public contact for the first photo`() {
        val listing = requireNotNull(filter.filter("Квартира в Ірпені\nЦіна 22 000${'$'}"))

        val overlay = requireNotNull(filter.photoOverlay(listing))

        assertEquals("Квартира в Ірпені", overlay.title)
        assertEquals("22 000${'$'}", overlay.price)
        assertEquals("066-372-71-02 Ірина", overlay.contact)
    }

    @Test
    fun `extracts programs excludes registration cost and renders the replaced phone`() {
        val source = """
            Гостомель
            Квартира в ЖК На Прорізній
            вул. Прорізна, 2
            Площа 44,3 м2
            Поверх 4/8
            Новий якісний ремонт
            Ціна 56000 ${'$'}
            Комісія 5%/2
            Оформлення - 12%
            Держ. програми - Так
            0961733824 Віта, АН Новатор
        """.trimIndent()

        val listing = requireNotNull(filter.filter(source))
        val tiktok = requireNotNull(filter.forTikTok(listing))

        assertEquals("Квартира в ЖК На Прорізній — Гостомель", listing.title)
        assertEquals("вул. Прорізна, 2", listing.address)
        assertNull(listing.registration)
        assertEquals("Держ. програми: Так", listing.governmentPrograms)
        assertEquals(listOf("Площа 44,3 м2", "Поверх 4/8"), listing.keyParameters)
        assertEquals(listOf("Новий якісний ремонт"), listing.additionalParameters)
        assertEquals(5, listing.hashtags.size)
        assertContains(tiktok, "🤙 066-372-71-02 Ірина")
        assertFalse(tiktok.contains("0961733824"))
        assertFalse(tiktok.contains("Комісія"))
        assertFalse(tiktok.contains("Оформлення"))
    }

    @Test
    fun `omits top floor and electric heating using a real log caption`() {
        val source = """
            Ірпінь
            ЖК Бургундія
            Студія з ремонтом
            Площа 24,5м2
            Опалення електричне
            Поверх 5/5
            Тепла підлога, посудомийка, варильна поверхня
            Ціна 45500${'$'}
            Оформлення 12%
            Готівка, сертифікат
        """.trimIndent()

        val listing = requireNotNull(filter.filter(source))
        val tiktok = requireNotNull(filter.forTikTok(listing))

        assertContains(listing.keyParameters, "Площа 24,5м2")
        assertNull(listing.registration)
        assertFalse(tiktok.contains("Поверх 5/5"))
        assertFalse(tiktok.contains("Опалення електричне"))
        assertFalse(tiktok.contains("Оформлення 12%"))
        assertContains(tiktok, "Тепла підлога, посудомийка, варильна поверхня")
    }

    @Test
    fun `keeps a non-top floor and non-electric heating`() {
        val source = """
            Квартира в Ірпені
            Поверх 4/5
            Газове опалення
            Ціна 60000${'$'}
        """.trimIndent()

        val tiktok = requireNotNull(filter.forTikTok(filter.filter(source)))

        assertContains(tiktok, "Поверх 4/5")
        assertContains(tiktok, "Газове опалення")
    }

    @Test
    fun `does not treat house storeys as an apartment top floor`() {
        val source = """
            Будинок в Ірпені
            Поверх 2/2
            Оформлення на першого власника
            Ціна 120000${'$'}
        """.trimIndent()

        val listing = requireNotNull(filter.filter(source))
        val tiktok = requireNotNull(filter.forTikTok(listing))

        assertContains(tiktok, "Поверх 2/2")
        assertEquals("Оформлення на першого власника", listing.registration)
        assertContains(tiktok, "Оформлення на першого власника")
    }

    @Test
    fun `omits assignment fees and boiler cost without treating them as listing price`() {
        val source = """
            Квартира в ЖК Бургундія
            Площа 44,59 м2
            Котел 690€
            Оф переуступка 5% + 500 грн / мкВ + додаткові метри
            Ціна 52500${'$'}
        """.trimIndent()

        val listing = requireNotNull(filter.filter(source))
        val tiktok = requireNotNull(filter.forTikTok(listing))

        assertEquals("52500${'$'}", listing.price)
        assertNull(listing.registration)
        assertFalse(tiktok.contains("Котел", ignoreCase = true))
        assertFalse(tiktok.contains("переуступка", ignoreCase = true))
        assertFalse(tiktok.contains("500 грн", ignoreCase = true))
    }
}
