package com.rieltor.application.service

import java.time.Duration
import java.time.Instant

/** Sliding window limiter: keeps one landing visitor from flooding the operator chat. */
class LandingLeadRateLimiter(
    private val limit: Int = DEFAULT_LIMIT,
    private val window: Duration = DEFAULT_WINDOW,
) {
    private val attempts = mutableMapOf<String, ArrayDeque<Instant>>()

    @Synchronized
    fun allow(clientId: String, now: Instant = Instant.now()): Boolean {
        val cutoff = now.minus(window)
        attempts.values.forEach { queue -> while (queue.firstOrNull()?.isBefore(cutoff) == true) queue.removeFirst() }
        attempts.entries.removeIf { it.value.isEmpty() }
        val queue = attempts.getOrPut(clientId) { ArrayDeque() }
        if (queue.size >= limit) return false
        queue.addLast(now)
        return true
    }

    private companion object {
        const val DEFAULT_LIMIT = 4
        val DEFAULT_WINDOW: Duration = Duration.ofMinutes(15)
    }
}
