package com.rieltor.application.service

import com.rieltor.domain.model.ListingMessage
import com.rieltor.domain.model.MediaTextOverlay

/** Presentation of already prepared public content; no source parsing. */
class ListingCaptionFormatter {
    fun forTikTok(listing: ListingMessage?): String? {
        listing ?: return null

        return buildList {
            add("$TITLE_PREFIX${listing.title}")
            listing.address?.let { add("📍 $it") }
            add("💰 ${listing.price}")
            if (listing.keyParameters.isNotEmpty()) {
                add("")
                listing.keyParameters.forEach { add("$ITEM_PREFIX$it") }
            }
            if (listing.additionalParameters.isNotEmpty()) {
                add("")
                listing.additionalParameters.forEach(::add)
            }
            listing.governmentPrograms?.let { add("🏦 $it") }
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
        const val PUBLIC_CONTACT_NAME = "Ірина"
        const val TITLE_PREFIX = "🏠 "
        const val ITEM_PREFIX = "• "
    }
}
