package com.rieltor.domain.model

import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
data class SourceMessage(
    val chatId: Long, val messageId: Long, val messageThreadId: Long,
    val mediaAlbumId: Long = 0, val text: String, val raw: String,
    val sourceCreatedAt: Long, val sourceEditedAt: Long = 0, val mediaIdentity: String = "",
) {
    val groupKey get() = "$chatId:${if (mediaAlbumId != 0L) "album:$mediaAlbumId" else "message:$messageId"}"
    fun fingerprint(): String = sha256("$text|$sourceEditedAt|$mediaIdentity|$messageThreadId")
}

fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

sealed interface SourceRefresh {
    data class Found(val message: SourceMessage) : SourceRefresh
    data object Deleted : SourceRefresh
    data object Unavailable : SourceRefresh
}

@Serializable
data class CatalogPhoto(val fileName: String, val sourceFileId: String, val sourceVersion: String,
    val width: Int, val height: Int, val checksum: String)

@Serializable
data class PublishAttempt(val attemptId: String, val status: String = "PREPARED", val publishId: String? = null,
    val mode: String = "POST", val createdAt: Long, val updatedAt: Long, val error: String? = null)

@Serializable
data class PublicationState(val attempts: List<PublishAttempt> = emptyList())

object CatalogCodes {
    val detailedTypes = setOf(
        "APARTMENT 1", "APARTMENT 1+",
        "APARTMENT 2", "APARTMENT 2+",
        "APARTMENT 3", "APARTMENT 3+",
        "HOUSE", "HOUSE+", "HOUSE-",
        "DUPLEX", "DUPLEX+",
        "LAND",
    )
    // Legacy values remain readable while previously imported listings are being refreshed.
    val legacyTypes = setOf("APARTMENT", "NEW_BUILD", "COMMERCIAL")
    val types = detailedTypes + legacyTypes
    val locations = setOf("IRPIN", "BUCHA", "VORZEL", "HOSTOMEL", "OTHER")
    val programs = setOf("EOSELIA", "VOUCHER", "CERTIFICATE", "POSTANOVA")

    fun category(type: String?): String = when {
        type?.startsWith("APARTMENT") == true -> "apartments"
        type?.startsWith("HOUSE") == true -> "houses"
        type?.startsWith("DUPLEX") == true -> "duplexes"
        type == "NEW_BUILD" -> "new-buildings"
        type == "LAND" -> "land"
        type == "COMMERCIAL" -> "commercial"
        else -> ""
    }

    fun apartmentRooms(type: String?): Int? = Regex("^APARTMENT ([123])(?:\\+)?$")
        .matchEntire(type.orEmpty())?.groupValues?.get(1)?.toInt()
}
