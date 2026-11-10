package com.rieltor.application.service

import com.rieltor.domain.model.*
import kotlin.test.*

class RepostCaptionFormatterTest {
    private val formatter = RepostCaptionFormatter()
    private val compactSelections = ConversationalCaptionVariant.entries.map {
        RepostCaptionSelection(RepostCaptionStyle.CONVERSATIONAL, conversationalVariant = it)
    } + BalancedCaptionVariant.entries.map {
        RepostCaptionSelection(RepostCaptionStyle.BALANCED, balancedVariant = it)
    }
    private val listing = ListingMessage(
        title = "Продам будинок",
        price = "135000 USD",
        address = "вул. Дніпровська",
        keyParameters = listOf("Площа: 152 м²", "Ділянка: 7,5 соток", "5 кімнат"),
        additionalParameters = listOf("Газ планують підключити наступного року", "Оздоблення White Box"),
        governmentPrograms = null, registration = null,
        hashtags = listOf("#будинок", "#Гостомель"), phone = "066-372-71-02",
    )

    @Test fun `standard caption remains byte for byte compatible`() {
        assertEquals(ListingCaptionFormatter().format(listing), formatter.format(listing, RepostCaptionStyle.STANDARD))
    }

    @Test fun `conversational caption leads with searchable facts and uses a short invitation`() {
        val text = formatter.format(listing, RepostCaptionStyle.CONVERSATIONAL, "Гостомель")
        assertTrue(text.startsWith("Продам будинок\n\n📍 Гостомель, вул. Дніпровська"))
        assertContains(text, "Газ планують підключити наступного року")
        assertContains(text, "Площа: 152 м²; Ділянка: 7,5 соток; 5 кімнат")
        assertContains(text, "Гостомель, вул. Дніпровська")
        assertContains(text, "Ціна: 135000 USD")
        assertTrue(text.endsWith("Перегляд — пишіть.\n066-372-71-02 Ірина"))
        assertFalse(text.contains("#"))
        assertFalse(text.contains("з газом"))
    }

    @Test fun `balanced caption combines structured facts with an invitation`() {
        val text = formatter.format(listing, RepostCaptionStyle.BALANCED, "Гостомель")
        assertTrue(text.startsWith("🏠 Продам будинок"))
        assertContains(text, "• Площа: 152 м²\n• Ділянка: 7,5 соток")
        assertContains(text, "Оздоблення White Box")
        assertContains(text, "Запис на перегляд — пишіть.")
        assertFalse(text.contains("#"))
    }

    @Test fun `three conversational variants retain property keywords and vary short endings and fact layouts`() {
        val texts = ConversationalCaptionVariant.entries.map { variant ->
            formatter.format(listing, RepostCaptionStyle.CONVERSATIONAL, "Гостомель", variant)
        }
        assertEquals(3, texts.toSet().size)
        assertTrue(texts[0].startsWith("Продам будинок"))
        assertTrue(texts[1].startsWith("Купити будинок"))
        assertTrue(texts[2].startsWith("Продам будинок"))
        assertContains(texts[0], "Перегляд — пишіть.")
        assertContains(texts[1], "Деталі — у повідомлення.")
        assertContains(texts[2], "Пишіть щодо перегляду.")
        assertContains(texts[1], "Площа: 152 м² · Ділянка: 7,5 соток")
        assertContains(texts[2], "Площа: 152 м²\nДілянка: 7,5 соток")
        for (text in texts) {
            assertContains(text, "Газ планують підключити наступного року")
            assertFalse(text.contains("з газом"))
            assertContains(text, "135000 USD")
            assertContains(text, "Гостомель, вул. Дніпровська")
            assertTrue(text.endsWith("066-372-71-02 Ірина"))
            assertTrue(text.length <= 500)
        }
    }

    @Test fun `three balanced variants change layout and invitation while preserving public facts`() {
        val texts = BalancedCaptionVariant.entries.map { variant ->
            formatter.format(listing, RepostCaptionStyle.BALANCED, "Гостомель", balancedVariant = variant)
        }
        assertEquals(3, texts.toSet().size)
        assertTrue(texts[0].startsWith("🏠 Продам будинок"))
        assertTrue(texts[1].startsWith("🏠 Продам будинок"))
        assertTrue(texts[2].startsWith("🏠 Продам будинок"))
        assertContains(texts[0], "• Площа: 152 м²\n• Ділянка: 7,5 соток")
        assertContains(texts[1], "Площа: 152 м² · Ділянка: 7,5 соток · 5 кімнат · Газ планують")
        assertTrue(texts[1].indexOf("📍") < texts[1].indexOf("Газ планують"))
        assertContains(texts[2], "— Площа: 152 м²\n— Ділянка: 7,5 соток")
        assertContains(texts[0], "Запис на перегляд — пишіть.")
        assertContains(texts[1], "Деталі — пишіть.")
        assertContains(texts[2], "Перегляд — за телефоном.")
        for (text in texts) {
            assertContains(text, "Газ планують підключити наступного року")
            assertFalse(text.contains("з газом"))
            assertContains(text, "135000 USD")
            assertContains(text, "Гостомель, вул. Дніпровська")
            assertTrue(text.endsWith("066-372-71-02 Ірина"))
            assertTrue((text + "\n\n#нерухомість").length <= 500)
        }
    }

    @Test fun `all compact variants prioritize location main parameters and supported program keywords`() {
        val apartment = listing.copy(
            title = "Продам 2-кімнатну квартиру",
            address = "ЖК Центральний",
            keyParameters = listOf("Площа: 64 м²", "2 кімнати", "Поверх: 3/10", "Індивідуальне опалення"),
            additionalParameters = listOf("Опис ".repeat(100), "З ремонтом"),
            governmentPrograms = "єОселя, єВідновлення",
        )
        for (selection in compactSelections) {
            val text = formatter.format(apartment, selection.style, "Ірпінь",
                selection.conversationalVariant ?: ConversationalCaptionVariant.DIRECT,
                selection.balancedVariant ?: BalancedCaptionVariant.STRUCTURED,
            )
            assertContains(text.substringBefore("\n"), "2-кімнатну квартиру")
            assertEquals("📍 Ірпінь, ЖК Центральний", text.split("\n\n")[1])
            for (fact in apartment.keyParameters + listOf("єОселя, єВідновлення", "З ремонтом")) {
                assertContains(text, fact)
            }
            assertTrue(text.indexOf("64 м²") < text.indexOf("єОселя"))
            assertTrue(text.indexOf("єОселя") < text.indexOf("З ремонтом"))
            assertTrue(text.lines().takeLast(2).first().length <= 30)
            assertTrue((text + "\n\n#нерухомість").length <= 500)
        }
    }

    @Test fun `search opening only replaces a known sale prefix and does not invent a property type`() {
        val text = formatter.format(listing.copy(title = "Комерційне приміщення"),
            RepostCaptionStyle.CONVERSATIONAL, conversationalVariant = ConversationalCaptionVariant.SEARCH)
        assertTrue(text.startsWith("Комерційне приміщення\n"))
        assertFalse(text.contains("Купити"))
        assertFalse(text.contains("квартиру"))
    }

    @Test fun `new styles fit Threads without cutting price or call to action and skip oversized facts`() {
        for (selection in compactSelections) {
            val text = formatter.format(listing.copy(
                title = "Продам будинок " + "опис ".repeat(100),
                address = "довга адреса ".repeat(100),
                keyParameters = listOf("довга характеристика ".repeat(100), "Ділянка: 7,5 соток"),
                additionalParameters = listOf("довгий опис ".repeat(100)),
            ), selection.style, "Гостомель",
                selection.conversationalVariant ?: ConversationalCaptionVariant.DIRECT,
                selection.balancedVariant ?: BalancedCaptionVariant.STRUCTURED,
            )
            assertTrue(text.length <= 500, "${selection}: ${text.length}")
            assertTrue((text + "\n\n#нерухомість").length <= 500)
            assertContains(text, "Ціна: 135000 USD")
            assertContains(text, "📍 Гостомель")
            assertContains(text, "Ділянка: 7,5 соток")
            assertTrue(text.endsWith("066-372-71-02 Ірина"))
        }
    }

    @Test fun `missing optional details do not produce empty bullets or fabricated features`() {
        val minimal = listing.copy(address = null, keyParameters = emptyList(), additionalParameters = emptyList())
        for (selection in compactSelections) {
            val text = formatter.format(minimal, selection.style,
                conversationalVariant = selection.conversationalVariant ?: ConversationalCaptionVariant.DIRECT,
                balancedVariant = selection.balancedVariant ?: BalancedCaptionVariant.STRUCTURED,
            )
            assertFalse(text.contains("null"))
            assertFalse(text.contains("•"))
            assertFalse(text.contains("Газ"))
            assertFalse(text.contains("єОселя"))
            assertContains(text, "135000 USD")
        }
    }
}
