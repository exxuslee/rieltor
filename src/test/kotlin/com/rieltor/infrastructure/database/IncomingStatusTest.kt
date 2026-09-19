package com.rieltor.infrastructure.database

import com.rieltor.infrastructure.database.model.IncomingStatus
import com.rieltor.infrastructure.database.model.IncomingStatusConverters
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IncomingStatusTest {
    @Test
    fun preservesExistingDatabaseAndJsonCodes() {
        val converters = IncomingStatusConverters()
        IncomingStatus.entries.forEach { status ->
            assertEquals(status, converters.decode(converters.encode(status)))
            val encoded = Json.encodeToString<IncomingStatus>(status)
            assertEquals("\"${status.code}\"", encoded)
            assertEquals(status, Json.decodeFromString<IncomingStatus>(encoded))
        }
    }

    @Test
    fun ordersPipelineStagesAndKeepsRetriesAtTheirStage() {
        val pipeline = listOf(
            IncomingStatus.WaitingStability, IncomingStatus.ReadyForMedia,
            IncomingStatus.Downloading, IncomingStatus.MediaReady, IncomingStatus.Promoted,
        )
        assertTrue(pipeline.zipWithNext().all { (before, after) -> before.order < after.order })
        assertEquals(IncomingStatus.WaitingStability.order, IncomingStatus.VerifyRetry.order)
        assertEquals(IncomingStatus.Downloading.order, IncomingStatus.MediaRetry.order)
        assertEquals(-1, IncomingStatus.NeedsReview.order)
        assertEquals(-1, IncomingStatus.Deleted.order)
    }
}
