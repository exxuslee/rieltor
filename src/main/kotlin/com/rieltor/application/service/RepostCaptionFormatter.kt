package com.rieltor.application.service

import com.rieltor.domain.model.BalancedCaptionVariant
import com.rieltor.domain.model.BalancedCaptionVariant.*
import com.rieltor.domain.model.ConversationalCaptionVariant
import com.rieltor.domain.model.ConversationalCaptionVariant.*
import com.rieltor.domain.model.ListingMessage
import com.rieltor.domain.model.RepostCaptionStyle

/** Leads with searchable listing facts and reuses only prepared public content. */
class RepostCaptionFormatter {
    private val standard = ListingCaptionFormatter()

    fun format(
        listing: ListingMessage,
        style: RepostCaptionStyle,
        location: String = "",
        conversationalVariant: ConversationalCaptionVariant = DIRECT,
        balancedVariant: BalancedCaptionVariant = STRUCTURED,
    ): String = when (style) {
        RepostCaptionStyle.STANDARD -> checkNotNull(standard.format(listing))
        RepostCaptionStyle.CONVERSATIONAL -> compact(listing, location, conversational = true, variant = conversationalVariant)
        RepostCaptionStyle.BALANCED -> compact(listing, location, conversational = false, balancedVariant = balancedVariant)
    }

    private fun compact(
        listing: ListingMessage, location: String, conversational: Boolean,
        variant: ConversationalCaptionVariant = DIRECT,
        balancedVariant: BalancedCaptionVariant = STRUCTURED,
    ): String {
        val salePrefix = Regex("(?iu)^продам\\s+")
        val subject = listing.title.replaceFirst(salePrefix, "")
            .trim().trimEnd('.', '!', '?')
            .let { if (salePrefix.containsMatchIn(listing.title)) it.replaceFirstChar(Char::lowercase) else it }
        val opening = when {
            conversational && variant == SEARCH && salePrefix.containsMatchIn(listing.title) -> "Купити $subject"
            conversational -> listing.title
            else -> "🏠 ${listing.title}"
        }
        val contact = if (conversational) when (variant) {
            DIRECT -> "Перегляд — пишіть."
            SEARCH -> "Деталі — у повідомлення."
            PERSONAL -> "Пишіть щодо перегляду."
        } else when (balancedVariant) {
            STRUCTURED -> "Запис на перегляд — пишіть."
            FEATURES -> "Деталі — пишіть."
            OVERVIEW -> "Перегляд — за телефоном."
        }
        val footer = "$contact\n${listing.phone} Ірина"
        val place = listOf(location, listing.address.orEmpty()).filter(String::isNotBlank).distinct().joinToString(", ")

        // Keep the property, location, price and contact ahead of optional details.
        val mandatory = buildList {
            if (place.isNotBlank()) add("📍 ${shorten(place, 90)}")
            add("Ціна: ${listing.price}")
        }
        val headerBudget = MAX_LENGTH - (mandatory + footer).joinToString("\n\n").length - 2
        val paragraphs = mutableListOf(shorten(opening, minOf(headerBudget, 140)))
        paragraphs += mandatory
        // Prioritize area, rooms and other main parameters, then supported program names
        // and features. Keep each fact intact: planned gas must not become connected gas.
        val candidates = listing.keyParameters.filter(String::isNotBlank).take(4) +
            listOfNotNull(listing.governmentPrograms?.takeIf(String::isNotBlank)) +
            listing.additionalParameters.filter(String::isNotBlank).take(2) +
            listOfNotNull(listing.registration)
        val selected = mutableListOf<String>()
        val separator = if (conversational) when (variant) {
            DIRECT -> "; "
            SEARCH -> " · "
            PERSONAL -> "\n"
        } else when (balancedVariant) {
            STRUCTURED -> "\n• "
            FEATURES -> " · "
            OVERVIEW -> "\n— "
        }
        val factPrefix = if (conversational || balancedVariant == FEATURES) ""
            else if (balancedVariant == OVERVIEW) "— " else "• "
        for (fact in candidates.map(String::trim).filter(String::isNotBlank).distinct()) {
            val proposed = selected + fact
            val block = factPrefix + proposed.joinToString(separator)
            if ((paragraphs + block + footer).joinToString("\n\n").length <= MAX_LENGTH) selected += fact
        }
        val facts = selected.takeIf { it.isNotEmpty() }
            ?.let { factPrefix + it.joinToString(separator) }
        return (paragraphs + listOfNotNull(facts) + footer).joinToString("\n\n")
    }

    private fun shorten(value: String, limit: Int): String =
        if (value.length <= limit) value else value.take((limit - 1).coerceAtLeast(0)).trimEnd() + "…"

    private companion object {
        // Threads appends a topic tag; leave room so it cannot cut off the contact.
        const val MAX_LENGTH = 480
    }
}
