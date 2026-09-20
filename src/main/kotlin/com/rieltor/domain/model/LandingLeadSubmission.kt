package com.rieltor.domain.model

/** Raw landing form submission as it arrives from the site, before validation. */
data class LandingLeadSubmission(
    val formType: String,
    val fields: Map<String, String>,
    val pageUrl: String,
    /** Honeypot field: bots fill it in, humans never see it. */
    val website: String = "",
)
