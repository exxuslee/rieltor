package com.rieltor.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class RepostCaptionStyle {
    STANDARD,
    CONVERSATIONAL,
    BALANCED;

    fun next(): RepostCaptionStyle = entries[(ordinal + 1) % entries.size]
}

@Serializable
enum class ConversationalCaptionVariant {
    DIRECT,
    SEARCH,
    PERSONAL;

    fun next(): ConversationalCaptionVariant = entries[(ordinal + 1) % entries.size]
}

data class RepostCaptionSelection(
    val style: RepostCaptionStyle,
    val conversationalVariant: ConversationalCaptionVariant? = null,
    val balancedVariant: BalancedCaptionVariant? = null,
)

@Serializable
enum class BalancedCaptionVariant {
    STRUCTURED,
    FEATURES,
    OVERVIEW;

    fun next(): BalancedCaptionVariant = entries[(ordinal + 1) % entries.size]
}
