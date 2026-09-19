package com.rieltor.infrastructure.database.model

import androidx.room.TypeConverter
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = IncomingStatusSerializer::class)
sealed class IncomingStatus(val code: String, val order: Int) {
    data object WaitingStability : IncomingStatus("WAITING_STABILITY", 0)
    data object VerifyRetry : IncomingStatus("VERIFY_RETRY", 0)
    data object ReadyForMedia : IncomingStatus("READY_FOR_MEDIA", 1)
    data object Downloading : IncomingStatus("DOWNLOADING", 2)
    data object MediaRetry : IncomingStatus("MEDIA_RETRY", 2)
    data object MediaReady : IncomingStatus("MEDIA_READY", 3)
    data object Promoted : IncomingStatus("PROMOTED", 4)

    // These states are outside the successful pipeline; -1 does not indicate progress.
    data object NeedsReview : IncomingStatus("NEEDS_REVIEW", -1)
    data object Deleted : IncomingStatus("DELETED", -1)

    companion object {
        val entries: List<IncomingStatus>
            get() = listOf(
                WaitingStability, VerifyRetry, ReadyForMedia, Downloading, MediaRetry,
                MediaReady, Promoted, NeedsReview, Deleted,
            )

        fun fromCode(code: String): IncomingStatus =
            requireNotNull(entries.find { it.code == code }) { "Unknown incoming status: $code" }
    }
}

object IncomingStatusSerializer : KSerializer<IncomingStatus> {
    override val descriptor = PrimitiveSerialDescriptor("IncomingStatus", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: IncomingStatus) = encoder.encodeString(value.code)
    override fun deserialize(decoder: Decoder): IncomingStatus = IncomingStatus.fromCode(decoder.decodeString())
}

class IncomingStatusConverters {
    @TypeConverter
    fun encode(status: IncomingStatus): String = status.code

    @TypeConverter
    fun decode(code: String): IncomingStatus = IncomingStatus.fromCode(code)
}
