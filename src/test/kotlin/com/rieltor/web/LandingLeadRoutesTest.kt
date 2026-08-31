package com.rieltor.web

import java.time.Duration
import java.time.Instant
import kotlin.test.*

class LandingLeadRoutesTest {
    private val validator = LandingLeadValidator()

    @Test
    fun `accepts a complete landing form from the public site`() {
        val lead = validator.validate(
            LandingLeadRequest(
                formType = "valuation",
                fields = mapOf(
                    "name" to "Ірина",
                    "phone" to "+38 (066) 372-71-02",
                    "address" to "Ірпінь",
                    "type" to "Квартира",
                    "note" to "  52 м²\n2 поверх  ",
                ),
                pageUrl = "https://rieltor.dpdns.org/sell-your-apartment.html?source=ad",
            )
        )

        assertNotNull(lead)
        assertTrue(lead.pageUrl.endsWith("/sell-your-apartment.html"))
        assertTrue(lead.fields.any { it.second == "52 м² 2 поверх" })
    }

    @Test
    fun `rejects a honeypot, unexpected field, or foreign page`() {
        val validFields = mapOf("category" to "Квартиру", "location" to "Ірпінь", "phone" to "+380663727102")

        assertNull(validator.validate(LandingLeadRequest("selection", validFields, "https://rieltor.dpdns.org/buy.html", "bot")))
        assertNull(validator.validate(LandingLeadRequest("selection", validFields + ("text" to "spam"), "https://rieltor.dpdns.org/buy.html")))
        assertNull(validator.validate(LandingLeadRequest("selection", validFields, "https://example.com/form")))
    }

    @Test
    fun `limits repeated requests from one visitor`() {
        val limiter = LandingLeadRateLimiter(limit = 2, window = Duration.ofMinutes(15))
        val now = Instant.parse("2026-08-31T12:00:00Z")

        assertTrue(limiter.allow("203.0.113.10", now))
        assertTrue(limiter.allow("203.0.113.10", now.plusSeconds(1)))
        assertFalse(limiter.allow("203.0.113.10", now.plusSeconds(2)))
        assertTrue(limiter.allow("203.0.113.10", now.plus(Duration.ofMinutes(16))))
    }
}
