package com.rieltor.infrastructure.oauth

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class OAuthStateStore(
    private val nowEpochSeconds: () -> Long = { Instant.now().epochSecond },
) {
    private val states = ConcurrentHashMap<String, Long>()

    fun issue(): String {
        val now = nowEpochSeconds()
        states.entries.removeIf { (_, issuedAt) -> now - issuedAt > STATE_TTL_SECONDS }
        return UUID.randomUUID().toString().also { state ->
            states[state] = now
        }
    }

    fun consume(state: String): Boolean {
        val issuedAt = states.remove(state) ?: return false
        return nowEpochSeconds() - issuedAt <= STATE_TTL_SECONDS
    }

    private companion object {
        const val STATE_TTL_SECONDS = 10 * 60L
    }
}
