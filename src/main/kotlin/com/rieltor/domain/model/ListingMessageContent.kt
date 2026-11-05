package com.rieltor.domain.model

/** Keep program details in one section when preparing a listing for publication. */
internal fun ListingMessage.withoutDuplicateProgramDetails(): ListingMessage {
    if (governmentPrograms.isNullOrBlank()) return this

    fun visibleDetails(details: List<String>): List<String> = details
        .flatMap { it.lines() }
        .filterNot(governmentProgramLine::containsMatchIn)
        .filter(String::isNotBlank)

    return copy(
        keyParameters = visibleDetails(keyParameters),
        additionalParameters = visibleDetails(additionalParameters),
    )
}

private val governmentProgramLine = Regex(
    """(?iuU)(?:держ(?:авн\p{L}*|\.)?\s*програм\p{L}*|\b(?:всі|усі)\s+програм\p{L}*|[єе]осел\p{L}*|сертиф[іи]кат\p{L}*|постанов\p{L}*|ваучер\p{L}*)"""
)
