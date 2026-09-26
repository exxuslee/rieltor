package com.rieltor.application.service

import com.rieltor.domain.model.CatalogCursor
import com.rieltor.domain.model.CatalogFilter
import com.rieltor.domain.model.CatalogSort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CatalogFilterParserTest {
    private val parser = CatalogFilterParser()

    @Test
    fun `accepts compact apartment codes and keeps stored codes compatible`() {
        assertEquals(
            listOf("APARTMENT1", "APARTMENT2+", "APARTMENT3"),
            parser.parse(mapOf("typeOfRealty" to listOf("APARTMENT1,APARTMENT2+,APARTMENT 3"))).types,
        )
    }

    @Test
    fun `parses source chat ids and rejects invalid ids`() {
        assertEquals(emptyList(), parser.parse(emptyMap()).chatIds)
        assertEquals(
            listOf(-1002681732909L, -1002691100301L),
            parser.parse(mapOf("chatId" to listOf("-1002681732909,-1002691100301", "-1002681732909"))).chatIds,
        )
        for (invalid in listOf("Novator", "-1002681732909 OR 1=1", "9223372036854775808")) {
            assertFailsWith<IllegalArgumentException> { parser.parse(mapOf("chatId" to listOf(invalid))) }
        }
    }

    @Test
    fun `splits multi value codes and applies defaults`() {
        val filter = parser.parse(
            mapOf(
                "location" to listOf("IRPIN,BUCHA"),
                "priceMin" to listOf("50000"),
                "sort" to listOf("priceAsc"),
            )
        )

        assertEquals(listOf("IRPIN", "BUCHA"), filter.locations)
        assertEquals(50_000L, filter.priceMin)
        assertEquals(CatalogSort.PRICE_ASC, filter.sort)
        assertEquals(CatalogFilter.DEFAULT_LIMIT, filter.limit)
    }

    @Test
    fun `rejects unknown codes, inverted ranges and oversized pages`() {
        assertFailsWith<IllegalArgumentException> { parser.parse(mapOf("location" to listOf("INVALID"))) }
        assertFailsWith<IllegalArgumentException> {
            parser.parse(mapOf("priceMin" to listOf("10"), "priceMax" to listOf("5")))
        }
        assertFailsWith<IllegalArgumentException> { parser.parse(mapOf("limit" to listOf("500"))) }
        assertFailsWith<IllegalArgumentException> { parser.parse(mapOf("cursor" to listOf("not-a-cursor"))) }
    }

    @Test
    fun `accepts a cursor only while the filters stay the same`() {
        val filters = mapOf("location" to listOf("IRPIN"))
        val cursor = CatalogCursorCodec().encode(CatalogCursor(parser.parse(filters).signature, 1_000, 7))

        assertEquals(7L, parser.parse(filters + ("cursor" to listOf(cursor))).cursor?.id)
        assertFailsWith<IllegalArgumentException> {
            parser.parse(mapOf("location" to listOf("BUCHA"), "cursor" to listOf(cursor)))
        }
    }
}
