package com.rieltor.domain.usecase

import com.rieltor.domain.model.ListingMessage
import kotlin.test.*

class PrepareListingContentUseCaseTest {
    private val prepareContent = PrepareListingContentUseCase()

    private fun publicText(listing: ListingMessage): String = (
        listOfNotNull(listing.title, listing.price, listing.address, listing.governmentPrograms, listing.registration) +
            listing.keyParameters + listing.additionalParameters + listing.hashtags + listing.phone
        ).joinToString("\n")

    @Test
    fun `removes abbreviated commission and registration cost lines`() {
        val listing = requireNotNull(prepareContent("""
            1-кімнатна квартира з ремонтом та меблями
            Ірпінь
            Повністю укомплектована меблями та технікою!
            5%/2
            2% Оф.
            71500${'$'}
        """.trimIndent()))

        val caption = publicText(listing)
        assertFalse(listing.additionalParameters.any { it.contains("%/2") || it.contains("Оф.") })
        assertFalse(caption.contains("5%/2"))
        assertFalse(caption.contains("2% Оф."))
    }

    @Test fun `recognizes studio and abbreviated apartment titles`() {
        listOf("Студія з ремонтом", "2кк з якісним ремонтом", "1к з новим ремонтом").forEach { title ->
            val listing = assertNotNull(prepareContent("Ірпінь\nЖК Приклад\n$title\nЦіна 76500$"))
            assertEquals("Продам ${title.replaceFirstChar(Char::lowercase)}", listing.title)
            assertEquals("ЖК Приклад", listing.address)
        }
    }

    @Test fun `builds sale titles for apartments houses townhouses and duplexes`() {
        listOf("1к з ремонтом", "дом", "таун", "дуплекс", "Будинок", "Таунхаус з ремонтом").forEach { type ->
            val listing = assertNotNull(prepareContent("Ірпінь\n$type\nЦіна 76500$"))
            assertEquals("Продам ${type.replaceFirstChar(Char::lowercase)}", listing.title)
        }
    }

    @Test fun `does not duplicate an existing sale prefix`() {
        listOf("Продам", "ПРОДАМ", "Продаж").forEach { prefix ->
            val listing = assertNotNull(prepareContent("• $prefix 1к з ремонтом.\nЦіна 76500$"))
            assertEquals("Продам 1к з ремонтом", listing.title)
        }
    }

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

        val listing = requireNotNull(prepareContent(source))
        assertEquals("Продам таунхаус з ремонтом", listing.title)
        assertEquals("175000${'$'}", listing.price)
        assertEquals("Вул Мечнікова", listing.address)
        assertEquals(listOf("Три кімнати", "84 кв.м", "ГАЗ"), listing.keyParameters)
        assertEquals(emptyList(), listing.additionalParameters)
        assertEquals("066-372-71-02", listing.phone)
        assertEquals(listOf("#нерухомість", "#таунхаус", "#Ірпінь"), listing.hashtags)
    }

    @Test
    fun `removes standalone drive labels after removing links and preserves descriptive text`() {
        listOf(
            "Диск",
            "ДИСК:",
            "• Диск: https://drive.google.com/drive/folders/example",
            "Google Drive: https://drive.google.com/drive/folders/example",
            "Диск\nhttps://drive.google.com/drive/folders/example",
        ).forEach { driveLine ->
            val listing = assertNotNull(prepareContent("""
                Будинок 2 поверхи 84 м2
                Навіс під два авто.
                За будинком ліс і березова роща.
                Дискова пила у господарському приміщенні
                $driveLine
                Ціна 148000 USD
            """.trimIndent()))

            assertEquals(listOf(
                "Навіс під два авто.",
                "За будинком ліс і березова роща.",
                "Дискова пила у господарському приміщенні",
            ), listing.additionalParameters, driveLine)
            assertEquals("#нерухомість", listing.hashtags.first())
        }
        assertNull(prepareContent("Диск"))
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

        val listing = requireNotNull(prepareContent(source))
        val result = publicText(listing)

        assertContains(listing.keyParameters, "Площа дуплекса: 93 м2")
        assertContains(result, "не введений в експлуатацію")
        assertEquals(listOf("#нерухомість", "#дуплекс", "#Буча"), listing.hashtags)
        assertFalse(result.contains("5000${'$'}/2"))
        assertFalse(result.contains("Вячеслав"))
        assertFalse(result.contains("google.com"))
    }

    @Test
    fun `returns null for an empty caption`() {
        assertNull(prepareContent(null))
        assertNull(prepareContent("  \n "))
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

        val listing = requireNotNull(prepareContent(source))
        val result = publicText(listing)

        assertEquals("45000${'$'}", listing.price)
        assertFalse(result.contains("5%/2"))
        assertFalse(result.contains("Сергій"))
        assertFalse(result.contains("Novator", ignoreCase = true))
        assertEquals("066-372-71-02", listing.phone)
    }

    @Test
    fun `removes spaced international phone and contact name with different line endings`() {
        listOf("\n", "\r\n", "\r").forEach { newline ->
            val source = listOf(
                "Квартира в Ірпені",
                "Площа 44 м2",
                "Ціна 52500${'$'}",
                "+380 98 651 26 33",
                "Таня",
            ).joinToString(newline)

            val listing = assertNotNull(prepareContent(source))

            assertEquals("Продам квартира в Ірпені", listing.title)
            assertEquals("52500${'$'}", listing.price)
            assertEquals(listOf("Площа 44 м2"), listing.keyParameters)
            assertTrue(listing.additionalParameters.isEmpty())
            assertEquals("066-372-71-02", listing.phone)
            assertNull(prepareContent("+380 98 651 26 33${newline}Таня"))
        }
    }

    @Test
    fun `removes any parenthesized note immediately after a price`() {
        val source = """
            Квартира в Ірпені
            Ціна 22 000${'$'} ( 2000/2)
        """.trimIndent()

        val listing = requireNotNull(prepareContent(source))
        val result = publicText(listing)

        assertEquals("22 000${'$'}", listing.price)
        assertFalse(result.contains("( 2000/2)"))
    }

    @Test
    fun `extracts programs excludes registration cost and replaces the phone`() {
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

        val listing = requireNotNull(prepareContent(source))
        val text = publicText(listing)

        assertEquals("Продам квартира в ЖК На Прорізній", listing.title)
        assertEquals("вул. Прорізна, 2", listing.address)
        assertNull(listing.registration)
        assertEquals("Держ. програми: Так", listing.governmentPrograms)
        assertEquals(listOf("Площа 44,3 м2", "Поверх 4/8"), listing.keyParameters)
        assertEquals(listOf("Новий якісний ремонт"), listing.additionalParameters)
        assertEquals(listOf("#нерухомість", "#квартира", "#Гостомель"), listing.hashtags)
        assertEquals("066-372-71-02", listing.phone)
        assertFalse(text.contains("0961733824"))
        assertFalse(text.contains("Комісія"))
        assertFalse(text.contains("Оформлення"))
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

        val listing = requireNotNull(prepareContent(source))
        val text = publicText(listing)

        assertContains(listing.keyParameters, "Площа 24,5м2")
        assertNull(listing.registration)
        assertFalse(text.contains("Поверх 5/5"))
        assertFalse(text.contains("Опалення електричне"))
        assertFalse(text.contains("Оформлення 12%"))
        assertContains(text, "Тепла підлога, посудомийка, варильна поверхня")
    }

    @Test
    fun `keeps a non-top floor and non-electric heating`() {
        val source = """
            Квартира в Ірпені
            Поверх 4/5
            Газове опалення
            Ціна 60000${'$'}
        """.trimIndent()

        val listing = assertNotNull(prepareContent(source))

        assertContains(listing.keyParameters, "Поверх 4/5")
        assertContains(listing.keyParameters, "Газове опалення")
    }

    @Test
    fun `does not treat house storeys as an apartment top floor`() {
        val source = """
            Будинок в Ірпені
            Поверх 2/2
            Оформлення на першого власника
            Ціна 120000${'$'}
        """.trimIndent()

        val listing = requireNotNull(prepareContent(source))

        assertContains(listing.keyParameters, "Поверх 2/2")
        assertEquals("Оформлення на першого власника", listing.registration)
    }

    @Test
    fun `omits assignment fee in accusative form`() {
        val listing = requireNotNull(prepareContent("Квартира в Ірпені\nПереуступку - 4%\nЦіна 52500${'$'}"))

        assertNull(listing.registration)
        assertEquals("52500${'$'}", listing.price)
        assertTrue(listing.keyParameters.isEmpty())
        assertTrue(listing.additionalParameters.isEmpty())
        assertFalse(publicText(listing).contains("4%"))
    }

    @Test
    fun `removes exclusive abbreviation as a word while preserving other words`() {
        val source = """
            Екс
            ЕКС Квартира в Ірпені
            Житловий комплекс
            Введений в експлуатацію
            Гарний ремонт екс.
            Ціна 52500${'$'}
        """.trimIndent()

        val listing = requireNotNull(prepareContent(source))

        assertEquals("Продам квартира в Ірпені", listing.title)
        assertEquals(
            listOf("Житловий комплекс", "Введений в експлуатацію", "Гарний ремонт"),
            listing.additionalParameters,
        )
        assertNull(prepareContent("Екс"))
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

        val listing = requireNotNull(prepareContent(source))
        val text = publicText(listing)

        assertEquals("52500${'$'}", listing.price)
        assertNull(listing.registration)
        assertFalse(text.contains("Котел", ignoreCase = true))
        assertFalse(text.contains("переуступка", ignoreCase = true))
        assertFalse(text.contains("500 грн", ignoreCase = true))
    }
}
