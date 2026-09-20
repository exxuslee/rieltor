package com.rieltor.domain.model

import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * A photo attached to the Telegram post itself.
 *
 * [uniqueId] is stable for the same image across re-downloads and is used as the catalog
 * photo identity; [remoteFileId] is what TDLib needs to fetch the bytes.
 */
@Serializable
data class SourcePhoto(
    val remoteFileId: String,
    val uniqueId: String,
    val width: Int = 0,
    val height: Int = 0,
    val fileSize: Long = 0,
    val messageId: Long = 0,
) {
    val identity: String get() = "$uniqueId:$width:$height"
}

@Serializable
data class SourceMessage(
    val chatId: Long,
    val messageId: Long,
    val messageThreadId: Long,
    val text: String,
    val raw: String,
    val sourceCreatedAt: Long,
    val sourceEditedAt: Long = 0,
    val mediaIdentity: String = "",
    val userId: Long? = null,
    /** Album photos of the post, in Telegram order. The first one is the listing cover. */
    val photos: List<SourcePhoto> = emptyList(),
) {
    fun fingerprint(): String = sha256("$text|$sourceEditedAt|$mediaIdentity|$messageThreadId|$userId")
}

/** Catalog photos taken from Telegram carry this version: a Telegram photo never changes in place. */
const val TELEGRAM_PHOTO_VERSION = "telegram"

fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

sealed interface SourceRefresh {
    data class Found(val message: SourceMessage) : SourceRefresh
    data object Deleted : SourceRefresh
    data object Unavailable : SourceRefresh
}

@Serializable
data class CatalogPhoto(
    val fileName: String, val sourceFileId: String, val sourceVersion: String,
    val width: Int, val height: Int, val checksum: String
)

@Serializable
data class PublishAttempt(
    val attemptId: String, val status: String = "PREPARED", val publishId: String? = null,
    val mode: String = "POST", val createdAt: Long, val updatedAt: Long, val error: String? = null
)

@Serializable
data class PublicationState(val attempts: List<PublishAttempt> = emptyList())

object CatalogCodes {

    val types = setOf(
        "APARTMENT 1", "APARTMENT 1+", "APARTMENT 2", "APARTMENT 2+", "APARTMENT 3", "APARTMENT 3+",
        "HOUSE", "HOUSE+", "HOUSE-", "DUPLEX", "DUPLEX+", "LAND",
    )
    private val locationNames = linkedMapOf(
        "IRPIN" to "Ірпінь",
        "BUCHA" to "Буча",
        "VORZEL" to "Ворзель",
        "HOSTOMEL" to "Гостомель",
        "STOYANKA" to "Стоянка",
        "HORENYCHI" to "Гореничі",
        "MYKHAILIVKA_RUBEZHIVKA" to "Михайлівка-Рубежівка",
        "BILOHORODKA" to "Білогородка",
        "DMYTRIVKA" to "Дмитрівка",
        "OTHER" to "Інша локація",
    )
    val locations: Set<String> = locationNames.keys
    val programs = setOf("EOSELIA", "VOUCHER", "CERTIFICATE", "POSTANOVA")

    /** Display name of a location code, empty when the code is unknown. */
    fun locationName(code: String?): String = code?.let(locationNames::get).orEmpty()

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
