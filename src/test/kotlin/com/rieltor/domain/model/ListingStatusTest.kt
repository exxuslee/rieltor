package com.rieltor.domain.model

import com.rieltor.infrastructure.database.model.ListingStatusConverters
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ListingStatusTest {
    @Test
    fun `existing status codes round-trip through JSON and database`() {
        val converter = ListingStatusConverters()
        val statuses = mapOf(
            "ACTIVE" to ListingStatus.Active,
            "NEEDS_REVIEW" to ListingStatus.NeedsReview,
            "HIDDEN" to ListingStatus.Hidden,
        )
        statuses.forEach { (code, status) ->
            assertEquals("\"$code\"", Json.encodeToString<ListingStatus>(status))
            assertEquals(status, Json.decodeFromString<ListingStatus>("\"$code\""))
            assertEquals(code, converter.encode(status))
            assertEquals(status, converter.decode(code))
        }
    }

    @Test
    fun `unknown status code is rejected`() {
        assertFailsWith<IllegalArgumentException> { ListingStatus.fromCode("INVALID") }
    }
}
