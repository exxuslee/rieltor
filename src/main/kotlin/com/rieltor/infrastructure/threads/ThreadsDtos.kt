package com.rieltor.infrastructure.threads

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class ThreadsTokenResponse(
    @SerialName("user_id")
    @Serializable(with = ThreadsUserIdSerializer::class)
    val userId: String? = null,
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val error: ThreadsApiError? = null,
)

/** Threads may return its numeric account identifier either as a JSON number or a string. */
@OptIn(ExperimentalSerializationApi::class)
object ThreadsUserIdSerializer : kotlinx.serialization.KSerializer<String?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ThreadsUserId", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return decoder.decodeString()
        val element = jsonDecoder.decodeJsonElement()
        val primitive = element as? JsonPrimitive
            ?: throw SerializationException("Threads user_id must be a JSON string or number.")
        if (primitive.isString || primitive.content.all(Char::isDigit)) return primitive.content
        throw SerializationException("Threads user_id must be a JSON string or number.")
    }

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }
}

@Serializable
data class ThreadsIdResponse(val id: String? = null, val error: ThreadsApiError? = null)

@Serializable
data class ThreadsContainerStatus(
    val id: String? = null,
    val status: String? = null,
    @SerialName("error_message") val errorMessage: String? = null,
    val error: ThreadsApiError? = null,
)

@Serializable
data class ThreadsApiError(
    val message: String = "Unknown Threads API error",
    val type: String? = null,
    val code: Int? = null,
    @SerialName("error_subcode") val errorSubcode: Int? = null,
)
