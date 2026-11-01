package com.rieltor.application.service

import com.rieltor.domain.model.ListingMessage
import com.rieltor.domain.model.MediaTextOverlay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ListingCaptionFormatterTest {
    private val formatter = ListingCaptionFormatter()
    private val listing = ListingMessage(
        title = "Продам квартиру",
        price = "50000 USD",
        address = "вул. Прикладна, 2",
        keyParameters = listOf("Площа 44 м²", "Поверх 4/8"),
        additionalParameters = listOf("Новий ремонт", "Тепла підлога"),
        governmentPrograms = "єОселя, Сертифікат",
        registration = "Оформлення на першого власника",
        hashtags = listOf("#квартира", "#Ірпінь"),
        phone = "066-372-71-02",
    )

    @Test
    fun `formats prepared content in the public caption layout`() {
        assertEquals("""
            🏠 Продам квартиру
            📍 вул. Прикладна, 2
            💰 50000 USD

            • Площа 44 м²
            • Поверх 4/8

            Новий ремонт
            Тепла підлога
            🏦 єОселя, Сертифікат
            📄 Оформлення на першого власника

            🤙 066-372-71-02 Ірина

            #квартира #Ірпінь
        """.trimIndent(), formatter.format(listing))
    }

    @Test
    fun `omits absent optional sections`() {
        assertEquals("""
            🏠 Продам квартиру
            💰 50000 USD

            🤙 066-372-71-02 Ірина

            #квартира #Ірпінь
        """.trimIndent(), formatter.format(listing.copy(
            address = null, keyParameters = emptyList(), additionalParameters = emptyList(),
            governmentPrograms = " ", registration = null,
        )))
    }

    @Test
    fun `uses the prepared title price and contact for the first photo`() {
        assertEquals(
            MediaTextOverlay(listing.title, listing.price, "066-372-71-02 Ірина"),
            formatter.photoOverlay(listing),
        )
    }

    @Test
    fun `returns null when content is absent`() {
        assertNull(formatter.format(null))
        assertNull(formatter.photoOverlay(null))
    }
}
