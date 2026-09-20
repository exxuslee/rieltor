package com.rieltor.application.service

import com.rieltor.application.model.LandingLeadSubmission
import com.rieltor.domain.model.LandingFormDefinition
import com.rieltor.domain.model.LandingForms
import com.rieltor.domain.model.LandingLead
import com.rieltor.domain.model.LandingLeadField
import java.net.URI

/** Accepts only known forms, filled by a human, submitted from our own pages. */
class LandingLeadValidator(
    private val allowedHosts: Set<String> = DEFAULT_ALLOWED_HOSTS,
) {
    fun validate(submission: LandingLeadSubmission): LandingLead? {
        if (submission.website.isNotBlank() || submission.fields.size > MAX_FIELD_COUNT) return null
        val definition = LandingForms[submission.formType] ?: return null
        if (submission.fields.keys.any { it !in definition.labels }) return null

        val fields = definition.labels.mapNotNull { (name, label) ->
            submission.fields[name]?.let(::clean)?.takeIf(String::isNotBlank)?.let { LandingLeadField(label, it) }
        }
        if (!hasRequiredFields(definition, fields)) return null
        if (fields.any { it.value.length > MAX_FIELD_LENGTH }) return null
        if (!hasValidPhone(definition, fields)) return null

        return normalisePageUrl(submission.pageUrl)?.let { LandingLead(definition.title, fields, it) }
    }

    private fun hasRequiredFields(definition: LandingFormDefinition, fields: List<LandingLeadField>): Boolean =
        definition.required.all { required -> fields.any { it.label == definition.label(required) } }

    private fun hasValidPhone(definition: LandingFormDefinition, fields: List<LandingLeadField>): Boolean =
        fields.any { it.label == definition.label(LandingForms.PHONE_FIELD) && PHONE.matches(it.value) }

    private fun normalisePageUrl(value: String): String? = runCatching {
        URI(value.trim()).let { uri ->
            if (uri.scheme !in ALLOWED_SCHEMES || uri.host !in allowedHosts) return null
            URI(uri.scheme, uri.authority, uri.path, null, null).toString().take(MAX_PAGE_URL_LENGTH)
        }
    }.getOrNull()

    private fun clean(value: String): String = value.replace(WHITESPACE, " ").trim()

    private companion object {
        const val MAX_FIELD_COUNT = 8
        const val MAX_FIELD_LENGTH = 1_000
        const val MAX_PAGE_URL_LENGTH = 500
        val WHITESPACE = Regex("\\s+")
        val PHONE = Regex("^[0-9+()\\- ]{7,25}$")
        val ALLOWED_SCHEMES = setOf("https", "http")
        val DEFAULT_ALLOWED_HOSTS = setOf("rieltor.dpdns.org", "localhost", "127.0.0.1")
    }
}
