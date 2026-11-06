package com.rieltor.application.service

import com.rieltor.domain.model.BalancedCaptionVariant
import com.rieltor.domain.model.BalancedCaptionVariant.*
import com.rieltor.domain.model.ConversationalCaptionVariant
import com.rieltor.domain.model.ConversationalCaptionVariant.*
import com.rieltor.domain.model.ListingMessage
import com.rieltor.domain.model.RepostCaptionStyle

/** Reuses only prepared public facts. The legacy format is deliberately unchanged. */
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
        val opening = if (conversational) when (variant) {
            DIRECT -> "Кому $subject?"
            SEARCH -> "Шукаєте $subject?"
            PERSONAL -> "Покажу вам ще один варіант.\n${listing.title}"
        } else when (balancedVariant) {
            STRUCTURED -> "🏠 ${listing.title}"
            FEATURES -> "Коротко про об’єкт:\n${listing.title}"
            OVERVIEW -> "Варіант для розгляду:\n${listing.title}"
        }
        val contact = if (conversational) when (variant) {
            DIRECT -> "Зацікавило? Пишіть — домовимося про перегляд!"
            SEARCH -> "Це те, що шукаєте? Напишіть — розповім деталі та запрошую на перегляд."
            PERSONAL -> "Хочете подивитися наживо? Пишіть — підберемо час для перегляду."
        } else when (balancedVariant) {
            STRUCTURED -> "Напишіть, щоб дізнатися деталі та записатися на перегляд."
            FEATURES -> "Зацікавив об’єкт? Напишіть — надішлю деталі та домовимося про перегляд."
            OVERVIEW -> "Потрібно більше інформації? Звертайтеся — відповім і запишу на перегляд."
        }
        val footer = "$contact\n${listing.phone} Ірина"
        val place = listOf(location, listing.address.orEmpty()).filter(String::isNotBlank).distinct().joinToString(", ")

        // Reserve price, location and the complete call to action before adding optional facts.
        val mandatory = buildList {
            if (place.isNotBlank()) add("📍 ${shorten(place, 90)}")
            add("Ціна: ${listing.price}")
        }
        val headerBudget = MAX_LENGTH - (mandatory + footer).joinToString("\n\n").length - 2
        val paragraphs = mutableListOf(shorten(opening, minOf(headerBudget, 140)))
        paragraphs += mandatory
        val details = (listing.additionalParameters + listing.keyParameters).filter(String::isNotBlank).distinct()
        val candidates = if (conversational) {
            // A short, intact feature first; never turn e.g. promised gas into connected gas.
            val feature = details.filter { it.length <= 140 }.take(1)
            val parameters = listing.keyParameters.filter(String::isNotBlank).take(3)
            if (variant == SEARCH) parameters + feature else feature + parameters
        } else {
            val parameters = listing.keyParameters.take(4)
            val features = listing.additionalParameters.take(2)
            (if (balancedVariant == FEATURES) features + parameters else parameters + features) +
                listOfNotNull(listing.governmentPrograms?.takeIf(String::isNotBlank), listing.registration)
        }
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
        for (fact in candidates.filter(String::isNotBlank).distinct()) {
            val proposed = selected + fact.trim()
            val block = factPrefix + proposed.joinToString(separator)
            if ((paragraphs + block + footer).joinToString("\n\n").length <= MAX_LENGTH) selected += fact.trim()
        }
        val facts = selected.takeIf { it.isNotEmpty() }
            ?.let { factPrefix + it.joinToString(separator) }
        return if (conversational || balancedVariant == FEATURES) {
            listOfNotNull(paragraphs.first(), facts, mandatory.joinToString("\n"), footer).joinToString("\n\n")
        } else {
            (paragraphs + listOfNotNull(facts) + footer).joinToString("\n\n")
        }
    }

    private fun shorten(value: String, limit: Int): String =
        if (value.length <= limit) value else value.take((limit - 1).coerceAtLeast(0)).trimEnd() + "…"

    private companion object {
        // Threads appends a topic tag; leave room so it cannot cut off the contact.
        const val MAX_LENGTH = 480
    }
}
