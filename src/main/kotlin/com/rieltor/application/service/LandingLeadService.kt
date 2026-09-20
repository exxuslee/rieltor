package com.rieltor.application.service

import com.rieltor.application.port.LandingLeadNotifier
import com.rieltor.domain.model.LandingLeadSubmission

/** Outcome of a landing submission; the transport layer decides which status code to return. */
enum class LandingLeadResult { ACCEPTED, RATE_LIMITED, INVALID, NOT_DELIVERED }

/** Full landing lead flow: rate limiting, validation and delivery. */
class LandingLeadService(
    private val validator: LandingLeadValidator,
    private val rateLimiter: LandingLeadRateLimiter,
    private val notifier: LandingLeadNotifier,
) {
    suspend fun submit(clientId: String, submission: LandingLeadSubmission): LandingLeadResult {
        if (!rateLimiter.allow(clientId)) return LandingLeadResult.RATE_LIMITED
        val lead = validator.validate(submission) ?: return LandingLeadResult.INVALID
        return if (notifier.notify(lead)) LandingLeadResult.ACCEPTED else LandingLeadResult.NOT_DELIVERED
    }
}
