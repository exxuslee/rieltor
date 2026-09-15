package com.rieltor.infrastructure.database.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "incoming_telegram_messages", indices = [
    Index(value = ["chatId", "messageId"], unique = true), Index(value = ["groupKey"]),
    Index(value = ["status", "verifyAfter"]), Index(value = ["legacyUpdateId"], unique = true),
])
@kotlinx.serialization.Serializable
data class IncomingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long, val messageId: Long?, val messageThreadId: Long,
    val mediaAlbumId: Long = 0, val groupKey: String,
    val originalRawMessage: String, val rawMessage: String, val rawText: String,
    val sourceCreatedAt: Long, val sourceEditedAt: Long = 0,
    val receivedAt: Long, val updatedAt: Long, val contentHash: String, val revision: Long = 1,
    val stableSince: Long, val verifyAfter: Long, val verifiedAt: Long? = null,
    val status: String = "WAITING_STABILITY", val googleDriveUrls: String = "[]",
    val mediaManifest: String = "[]", val attemptCount: Int = 0, val nextAttemptAt: Long = 0,
    val lastError: String? = null, val leaseToken: String? = null, val leaseUntil: Long = 0,
    val listingId: Long? = null, val promotedAt: Long? = null, val deletedAt: Long? = null,
    val legacyUpdateId: Long? = null,
)

@Entity(tableName = "listings", indices = [
    Index(value = ["groupKey"], unique = true), Index(value = ["chatId", "messageId"], unique = true),
    Index(value = ["status", "sourceCreatedAt", "id"]),
    Index(value = ["status", "location", "typeOfRealty", "currency", "price"]),
    Index(value = ["status", "tiktokStatus", "sourceCreatedAt"]),
    Index(value = ["status", "threadsStatus", "sourceCreatedAt"]), Index(value = ["legacyUpdateId"], unique = true),
])
@kotlinx.serialization.Serializable
data class ListingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupKey: String, val chatId: Long, val messageId: Long?, val messageThreadId: Long,
    val mediaAlbumId: Long = 0, val rawMessage: String, val sourceRevision: String,
    val title: String = "", val description: String = "", val location: String? = null,
    val address: String? = null, val district: String? = null, val typeOfRealty: String? = null,
    val transactionType: String = "SALE", val tags: String = "[]",
    val primeParams: String = "{}", val secondaryParams: String = "{}",
    val governmentPrograms: String = "[]", val governmentProgramsKnown: Boolean = false,
    val googleDriveUrl: String? = null, val googleDriveUrls: String = "[]",
    val price: Long? = null, val currency: String? = null, val pricePeriod: String = "TOTAL",
    val areaM2: Double? = null, val landAreaSotka: Double? = null, val rooms: Int? = null,
    val floor: Int? = null, val totalFloors: Int? = null, val photos: String = "[]",
    val coverPhotoId: String? = null, val status: String = "NEEDS_REVIEW",
    val sourceCreatedAt: Long, val receivedAt: Long, val cdt: Long, val updatedAt: Long,
    val publishedAt: Long? = null,
    val tiktokReposted: Boolean = false, val threadsReposted: Boolean = false,
    val tiktokRepostedAt: Long? = null, val threadsRepostedAt: Long? = null,
    val tiktokStatus: String = "PENDING", val threadsStatus: String = "PENDING",
    val tiktokPublishId: String? = null, val threadsPublishId: String? = null,
    val tiktokState: String = "{\"attempts\":[]}", val threadsState: String = "{\"attempts\":[]}",
    val parserVersion: Int = 1, val parseWarnings: String = "[]", val legacyUpdateId: Long? = null,
    val legacySnapshot: String? = null,
)

