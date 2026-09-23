package com.rieltor.domain.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class RepostStatusTest {
    @Test
    fun `status codes round-trip through serialization`() {
        RepostStatus.entries.forEach { status ->
            val encoded = Json.encodeToString<RepostStatus>(status)
            assertEquals(status, Json.decodeFromString<RepostStatus>(encoded))
            assertEquals(status, RepostStatus.fromCode(status.code))
        }
    }
}
