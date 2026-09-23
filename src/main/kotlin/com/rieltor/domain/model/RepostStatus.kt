package com.rieltor.domain.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Lifecycle state of a listing repost attempt. */
@Serializable(with = RepostStatusSerializer::class)
sealed class RepostStatus(val code: String) {
    data object Pending : RepostStatus("PENDING")
    data object Prepared : RepostStatus("PREPARED")
    data object Sending : RepostStatus("SENDING")
    data object AwaitingConfirmation : RepostStatus("AWAITING_CONFIRMATION")
    data object DeliveredDraft : RepostStatus("DELIVERED_DRAFT")
    data object Published : RepostStatus("PUBLISHED")
    data object Abandoned : RepostStatus("ABANDONED")
    data object Unknown : RepostStatus("UNKNOWN")
    data object Failed : RepostStatus("FAILED")

    companion object {
        val entries: List<RepostStatus>
            get() = listOf(
                Pending, Prepared, Sending, AwaitingConfirmation, DeliveredDraft,
                Published, Abandoned, Unknown, Failed,
            )

        fun fromCode(code: String): RepostStatus =
            requireNotNull(entries.find { it.code == code }) { "Unknown repost status: $code" }
    }
}

object RepostStatusSerializer : KSerializer<RepostStatus> {
    override val descriptor = PrimitiveSerialDescriptor("RepostStatus", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: RepostStatus) = encoder.encodeString(value.code)
    override fun deserialize(decoder: Decoder): RepostStatus = RepostStatus.fromCode(decoder.decodeString())
}
