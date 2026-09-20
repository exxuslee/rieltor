package com.rieltor.web.dto

import com.rieltor.application.model.LandingLeadSubmission
import kotlinx.serialization.Serializable

/** Landing form payload as posted by the public site. */
@Serializable
data class LandingLeadRequest(
    val formType: String,
    val fields: Map<String, String>,
    val pageUrl: String,
    val website: String = "",
) {
    fun toSubmission(): LandingLeadSubmission = LandingLeadSubmission(formType, fields, pageUrl, website)
}
