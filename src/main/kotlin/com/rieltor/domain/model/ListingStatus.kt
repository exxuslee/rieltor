package com.rieltor.domain.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Lifecycle state of a catalog listing. */
@Serializable(with = ListingStatusSerializer::class)
sealed class ListingStatus(val code: String) {
    data object Active : ListingStatus("ACTIVE")
    data object NeedsReview : ListingStatus("NEEDS_REVIEW")
    data object Hidden : ListingStatus("HIDDEN")

    companion object {
        val entries: List<ListingStatus>
            get() = listOf(Active, NeedsReview, Hidden)

        fun fromCode(code: String): ListingStatus =
            requireNotNull(entries.find { it.code == code }) { "Unknown listing status: $code" }
    }
}

object ListingStatusSerializer : KSerializer<ListingStatus> {
    override val descriptor = PrimitiveSerialDescriptor("ListingStatus", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ListingStatus) = encoder.encodeString(value.code)
    override fun deserialize(decoder: Decoder): ListingStatus = ListingStatus.fromCode(decoder.decodeString())
}
