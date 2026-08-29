package com.rieltor.infrastructure.oauth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OAuthStateStoreTest {
    @Test
    fun `state is valid only once`() {
        val store = OAuthStateStore { 1_000L }
        val state = store.issue()

        assertTrue(store.consume(state))
        assertFalse(store.consume(state))
    }

    @Test
    fun `state expires after ten minutes`() {
        var now = 1_000L
        val store = OAuthStateStore { now }
        val state = store.issue()

        now += 601

        assertFalse(store.consume(state))
    }
}
