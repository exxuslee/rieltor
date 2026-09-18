package com.rieltor.domain.service

import com.rieltor.infrastructure.database.model.IncomingEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class CatalogListingParserTest {
    @Test fun `extracts exact price and programs without inventing eligibility`() {
        val text = "Ірпінь\n2 кімнатна квартира\nЦіна: 82 000,50 USD\nПлоща: 64 м²\nєОселя\nСертифікат не розглядаємо\nhttps://drive.google.com/drive/folders/example"
        val row = IncomingEntity(chatId = -1, messageId = 1, messageThreadId = 2, groupKey = "g",
            rawMessage = text, rawText = text, sourceCreatedAt = 0, receivedAt = 0, contentHash = "h", verifyAfter = 0)
        val result = CatalogListingParser().parse(listOf(row), "APARTMENT 2", 1)
        assertEquals(82_001, result.price)
        assertEquals("ACTIVE", result.status)
        assertEquals("[\"EOSELIA\"]", result.governmentPrograms)
        assertEquals(64.0, result.areaM2)
        assertEquals("NEEDS_REVIEW", CatalogListingParser().parse(listOf(row), null, 1).status)
    }

    @Test fun `takes apartment room count from configured topic type`() {
        val text = "Ірпінь\nКвартира з ремонтом\nЦіна: 70 000 USD\nПлоща: 48 м²\nhttps://drive.google.com/drive/folders/example"
        val row = IncomingEntity(chatId = -1, messageId = 2, messageThreadId = 3, groupKey = "rooms",
            rawMessage = text, rawText = text, sourceCreatedAt = 0, receivedAt = 0, contentHash = "h", verifyAfter = 0)

        val result = CatalogListingParser().parse(listOf(row), "APARTMENT 3+", 1)

        assertEquals(3, result.rooms)
        assertEquals("ACTIVE", result.status)
    }

    @Test fun `uses other location when city is not specified`() {
        val text = "ЖК Сенсація\nКвартира з ремонтом\nЦіна: 35 000 USD\nПлоща: 24 м²\nhttps://drive.google.com/drive/folders/example"
        val row = IncomingEntity(chatId = -1, messageId = 3, messageThreadId = 4, groupKey = "no-city",
            rawMessage = text, rawText = text, sourceCreatedAt = 0, receivedAt = 0, contentHash = "h", verifyAfter = 0)

        val result = CatalogListingParser().parse(listOf(row), "APARTMENT 1", 1)

        assertEquals("OTHER", result.location)
        assertEquals("ACTIVE", result.status)
    }

    @Test fun `recognizes unlabeled price and bargain from real listing wording`() {
        val result = parse("""
            Ірпінь
            ЖК Контраст
            2кк з якісним ремонтом
            77,3 м2
            150000${'$'}(можливий торг)
            Всі держпрограми -Так.
            https://drive.google.com/drive/folders/example
        """.trimIndent())

        assertEquals(150_000, result.price)
        assertEquals("ACTIVE", result.status)
        assertEquals(ALL_PROGRAMS_JSON, result.governmentPrograms)
        assertEquals("Торг", Json.parseToJsonElement(result.secondaryParams).jsonObject["bargain"]?.jsonPrimitive?.content)
    }

    @Test fun `extracts unlabeled area with a square-meter unit from database examples`() {
        mapOf(
            "5 /15 поверх 54 м2" to 54.0,
            "• 85 м², • 3 сотки" to 85.0,
            "41,6м2 з гардеробом 6/9 поверх" to 41.6,
        ).forEach { (details, expectedArea) ->
            assertEquals(expectedArea, parse(baseListing(details)).areaM2, details)
        }
    }

    @Test fun `does not use a price per square meter as the property area`() {
        assertEquals(null, parse(baseListing("Право власності 500 грн м2")).areaM2)
    }

    @Test fun `prefers new price and ignores commission and storage price`() {
        val result = parse("""
            Буча
            2к квартира з ремонтом
            Ціна 80 000${'$'}
            Нова ціна 79000${'$'} торг (4000${'$'}/2)
            Є кладова 2,7м2 ціна 3000${'$'}
            https://drive.google.com/drive/folders/example
        """.trimIndent())

        assertEquals(79_000, result.price)
        assertEquals("ACTIVE", result.status)
    }

    @Test fun `recognizes standalone price while ignoring discount amount`() {
        val result = parse("""
            Ірпінь
            2к квартира
            Зниження ціни -500${'$'}
            131 000${'$'} (5%/2)
            https://drive.google.com/drive/folders/example
        """.trimIndent())

        assertEquals(131_000, result.price)
        assertEquals("ACTIVE", result.status)
    }

    @Test fun `supports common price spellings from supplied listings`() {
        mapOf(
            "Ціна - 100.000${'$'} (5/2)" to 100_000L,
            "Ціна:115.500${'$'} (5500${'$'}/2)" to 115_500L,
            "83000${'$'} (4000/2)" to 83_000L,
            "Ціна 75000${'$'} (комісія 4000/2)" to 75_000L,
            "Вартість 100000${'$'} (комісія 5%/2)" to 100_000L,
        ).forEach { (priceLine, expected) ->
            val result = parse(baseListing(priceLine = priceLine))
            assertEquals(expected, result.price, priceLine)
            assertEquals("ACTIVE", result.status, priceLine)
        }
    }

    @Test fun `uses last ordinary price when listing contains a price reduction`() {
        val result = parse(baseListing(priceLine = "Ціна 69000${'$'}\nЦіна 65000${'$'}"))

        assertEquals(65_000, result.price)
    }

    @Test fun `defaults to every government program`() {
        assertEquals(ALL_PROGRAMS_JSON, parse(baseListing()).governmentPrograms)
        assertEquals(ALL_PROGRAMS_JSON, parse(baseListing("Держпрограми обговорюються")).governmentPrograms)
        assertEquals(ALL_PROGRAMS_JSON, parse(baseListing("Всі державні програми!!")).governmentPrograms)
    }

    @Test fun `explicit program list does not invent omitted programs`() {
        assertEquals(
            "[\"VOUCHER\",\"CERTIFICATE\"]",
            parse(baseListing("Сертифікат, ваучер - так")).governmentPrograms,
        )
        assertEquals(
            "[\"VOUCHER\",\"CERTIFICATE\",\"POSTANOVA\"]",
            parse(baseListing("#Ваучер #Сертифікат #Постанова")).governmentPrograms,
        )
    }

    private fun parse(text: String) = CatalogListingParser().parse(
        listOf(IncomingEntity(chatId = -1, messageId = 10, messageThreadId = 20, groupKey = "example",
            rawMessage = text, rawText = text, sourceCreatedAt = 0, receivedAt = 0, contentHash = "h", verifyAfter = 0)),
        "APARTMENT 2", 1,
    )

    private fun baseListing(programs: String? = null, priceLine: String = "Ціна 100000${'$'}") = buildList {
        add("Ірпінь")
        add("2к квартира")
        add(priceLine)
        programs?.let(::add)
        add("https://drive.google.com/drive/folders/example")
    }.joinToString("\n")

    private companion object {
        const val ALL_PROGRAMS_JSON = "[\"EOSELIA\",\"VOUCHER\",\"CERTIFICATE\",\"POSTANOVA\"]"
    }
}
