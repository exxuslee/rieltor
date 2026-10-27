package com.rieltor.application.service

import com.rieltor.infrastructure.database.model.AdEntity
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class ListingCaptionFormatterTest {
    private val formatter = ListingCaptionFormatter()
    private val row = AdEntity(
        chatId = -1, messageId = 1, adId = "programs", messageThreadId = 0,
        sourceRevision = "test", timestamp = 0,
        title = "ЖК Синергія Сіті", price = 91000, currency = "USD",
        primeParams = """{"details":["Площа 39 м","Поверх 5","Державні програми:","ЄОселя ВПОПостанови#Сертифікат"]}""",
        description = "Держ. програми: так\nВаучер\nТепла підлога",
        governmentPrograms = """["EOSELIA","CERTIFICATE","POSTANOVA"]""",
    )

    @Test
    fun `catalog caption shows programs only in their separate section`() {
        val caption = assertNotNull(formatter.forCatalog(row, "066-372-71-02"))

        assertContains(caption, "🏦 єОселя, Сертифікат, Постанова")
        assertFalse(caption.contains("Державні програми:"))
        assertFalse(caption.contains("ЄОселя ВПОПостанови#Сертифікат"))
        assertFalse(caption.contains("Держ. програми:"))
        assertFalse(caption.contains("Ваучер"))
        assertContains(caption, "• Площа 39 м")
        assertContains(caption, "• Поверх 5")
        assertContains(caption, "Тепла підлога")
    }

    @Test
    fun `preserves program details when there is no separate section`() {
        val caption = assertNotNull(formatter.forCatalog(row.copy(governmentPrograms = "[]"), "066-372-71-02"))

        assertContains(caption, "• Державні програми:")
        assertContains(caption, "• ЄОселя ВПОПостанови#Сертифікат")
        assertContains(caption, "Ваучер")
        assertFalse(caption.contains("🏦"))
    }

    @Test
    fun `removes program lines inside a multiline parameter preserving other lines`() {
        val multiline = row.copy(primeParams = """{"details":["Площа 39 м\nДержавні програми:\nЄОселя\nПоверх 5"]}""")
        val caption = assertNotNull(formatter.forCatalog(multiline, "066-372-71-02"))

        assertContains(caption, "• Площа 39 м")
        assertContains(caption, "• Поверх 5")
        assertFalse(caption.contains("Державні програми:"))
        assertFalse(caption.contains("• ЄОселя"))
    }
}
