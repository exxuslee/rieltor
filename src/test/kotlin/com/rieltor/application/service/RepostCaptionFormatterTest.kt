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

    @Test fun `conversational caption invites a reply and preserves facts price place and contact`() {
        val text = formatter.format(listing, RepostCaptionStyle.CONVERSATIONAL, "Гостомель")
        assertTrue(text.startsWith("Кому будинок?"))
        assertContains(text, "Газ планують підключити наступного року")
        assertContains(text, "Площа: 152 м²; Ділянка: 7,5 соток; 5 кімнат")
        assertContains(text, "Гостомель, вул. Дніпровська")
        assertContains(text, "Ціна: 135000 USD")
        assertTrue(text.endsWith("Зацікавило? Пишіть — домовимося про перегляд!\n066-372-71-02 Ірина"))
        assertFalse(text.contains("#"))
        assertFalse(text.contains("з газом"))
    }

    @Test fun `balanced caption combines structured facts with an invitation`() {
        val text = formatter.format(listing, RepostCaptionStyle.BALANCED, "Гостомель")
        assertTrue(text.startsWith("🏠 Продам будинок"))
        assertContains(text, "• Площа: 152 м²\n• Ділянка: 7,5 соток")
        assertContains(text, "Оздоблення White Box")
        assertContains(text, "Напишіть, щоб дізнатися деталі та записатися на перегляд.")
        assertFalse(text.contains("#"))
    }

    @Test fun `three conversational variants have different openings endings and fact layouts`() {
        val texts = ConversationalCaptionVariant.entries.map { variant ->
            formatter.format(listing, RepostCaptionStyle.CONVERSATIONAL, "Гостомель", variant)
        }
        assertEquals(3, texts.toSet().size)
        assertTrue(texts[0].startsWith("Кому будинок?"))
        assertTrue(texts[1].startsWith("Шукаєте будинок?"))
        assertTrue(texts[2].startsWith("Покажу вам ще один варіант.\nПродам будинок"))
        assertContains(texts[0], "Зацікавило? Пишіть")
        assertContains(texts[1], "Це те, що шукаєте? Напишіть")
        assertContains(texts[2], "Хочете подивитися наживо? Пишіть")
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
        assertTrue(texts[1].startsWith("Коротко про об’єкт:\nПродам будинок"))
        assertTrue(texts[2].startsWith("Варіант для розгляду:\nПродам будинок"))
        assertContains(texts[0], "• Площа: 152 м²\n• Ділянка: 7,5 соток")
        assertContains(texts[1], "Газ планують підключити наступного року · Оздоблення White Box · Площа: 152 м²")
        assertTrue(texts[1].indexOf("Газ планують") < texts[1].indexOf("📍"))
        assertContains(texts[2], "— Площа: 152 м²\n— Ділянка: 7,5 соток")
        assertContains(texts[0], "Напишіть, щоб дізнатися деталі")
        assertContains(texts[1], "Зацікавив об’єкт? Напишіть")
        assertContains(texts[2], "Потрібно більше інформації? Звертайтеся")
        for (text in texts) {
            assertContains(text, "Газ планують підключити наступного року")
            assertFalse(text.contains("з газом"))
            assertContains(text, "135000 USD")
            assertContains(text, "Гостомель, вул. Дніпровська")
            assertTrue(text.endsWith("066-372-71-02 Ірина"))
            assertTrue((text + "\n\n#нерухомість").length <= 500)
        }
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
            assertContains(text, "135000 USD")
        }
    }
}
