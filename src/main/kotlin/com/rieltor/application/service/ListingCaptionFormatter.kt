package com.rieltor.application.service

import com.rieltor.domain.model.CatalogCodes
import com.rieltor.domain.model.ListingMessage
import com.rieltor.domain.model.MediaTextOverlay
import com.rieltor.domain.usecase.PrepareListingContentUseCase
import com.rieltor.infrastructure.database.model.AdEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Presentation of already prepared public content; no source parsing. */
class ListingCaptionFormatter {
    private val prepareContent = PrepareListingContentUseCase()

    fun forCatalog(row: AdEntity, phone: String): String? = forTikTok(
        ListingMessage(
            title = row.title,
            price = "${requireNotNull(row.price)} ${row.currency}",
            address = row.address,
            keyParameters = Json.parseToJsonElement(row.primeParams).jsonObject["details"]
                ?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
            additionalParameters = prepareContent(row.rawText)?.additionalParameters
                ?: row.description.lines().filter { it.isNotBlank() },
            governmentPrograms = Json.decodeFromString<List<String>>(row.governmentPrograms)
                .joinToString(transform = CatalogCodes::programName).takeIf { it.isNotEmpty() },
            registration = null,
            hashtags = Json.decodeFromString(row.tags),
            phone = phone,
        )
    )

    fun forTikTok(listing: ListingMessage?): String? {
        listing ?: return null
        val governmentPrograms = listing.governmentPrograms?.takeIf(String::isNotBlank)
        fun visibleDetails(details: List<String>): List<String> = if (governmentPrograms == null) details else
            details.flatMap { it.lines() }.filterNot(governmentProgramLine::containsMatchIn)
                .filter(String::isNotBlank)
        val keyParameters = visibleDetails(listing.keyParameters)
        val additionalParameters = visibleDetails(listing.additionalParameters)

        return buildList {
            add("$TITLE_PREFIX${listing.title}")
            listing.address?.let { add("📍 $it") }
            add("💰 ${listing.price}")
            if (keyParameters.isNotEmpty()) {
                add("")
                keyParameters.forEach { add("$ITEM_PREFIX$it") }
            }
            if (additionalParameters.isNotEmpty()) {
                add("")
                additionalParameters.forEach(::add)
            }
            governmentPrograms?.let { add("🏦 $it") }
            listing.registration?.let { add("📄 $it") }
            add("")
            add("🤙 ${listing.phone} $PUBLIC_CONTACT_NAME")
            add("")
            add(listing.hashtags.joinToString(" "))
        }.joinToString("\n")
    }

    fun photoOverlay(listing: ListingMessage?): MediaTextOverlay? = listing?.let {
        MediaTextOverlay(it.title, it.price, "${it.phone} $PUBLIC_CONTACT_NAME")
    }

    private companion object {
        val governmentProgramLine = Regex(
            """(?iu)(?:держ(?:авн\p{L}*|\.)?\s*програм\p{L}*|[єе]осел\p{L}*|сертиф[іи]кат\p{L}*|постанов\p{L}*|ваучер\p{L}*)"""
        )
        const val PUBLIC_CONTACT_NAME = "Ірина"
        const val TITLE_PREFIX = "🏠 "
        const val ITEM_PREFIX = "• "
    }
}
