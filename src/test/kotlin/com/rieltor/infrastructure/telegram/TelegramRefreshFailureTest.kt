package com.rieltor.infrastructure.telegram

import it.tdlight.client.TelegramError
import it.tdlight.jni.TdApi
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TelegramRefreshFailureTest {
    @Test
    fun `recognizes message not found wrapped by future`() {
        val error = ExecutionException(TelegramError(TdApi.Error(404, "Not Found")))

        assertEquals(TelegramRefreshFailure.MESSAGE_NOT_FOUND, error.telegramRefreshFailure())
    }

    @Test
    fun `recognizes wrapped timeout`() {
        val error = ExecutionException(TimeoutException("timed out"))

        assertEquals(TelegramRefreshFailure.TIMEOUT, error.telegramRefreshFailure())
    }

    @Test
    fun `leaves unexpected errors unclassified`() {
        assertNull(ExecutionException(IllegalStateException("boom")).telegramRefreshFailure())
    }
}
