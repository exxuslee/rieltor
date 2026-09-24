package com.rieltor.infrastructure.database.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.rieltor.domain.model.ListingStatus

@Entity(
    tableName = "incomeTab", indices = [
        Index(value = ["chatId", "messageId"], unique = true),
        Index(value = ["status", "verifyAfter"]),
    ]
)
@kotlinx.serialization.Serializable
@androidx.room.TypeConverters(IncomingStatusConverters::class)
data class IncomingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long,
    val messageId: Long?,
    val messageThreadId: Long,
    val userId: Long? = null,
    val rawText: String,
    val sourceCreatedAt: Long,
    val sourceEditedAt: Long = 0,
    val contentHash: String,
    val revision: Long = 1,
    val verifyAfter: Long,
    val verifiedAt: Long? = null,
    val status: IncomingStatus = IncomingStatus.WaitingStability,
    val mediaManifest: String = "[]",
    /** Photos attached to the Telegram post itself, serialized as a list of SourcePhoto. */
    val sourcePhotos: String = "[]",
    val attemptCount: Int = 0,
    val nextAttemptAt: Long = 0,
)

@Entity(
    tableName = "adsTab", indices = [
        Index(value = ["adId"], unique = true), Index(value = ["chatId", "messageId"], unique = true),
        Index(value = ["status", "sourceCreatedAt", "id"]),
        Index(value = ["status", "location", "typeOfRealty", "currency", "price"]),
        Index(value = ["status", "tiktokStatus", "sourceCreatedAt"]),
        Index(value = ["status", "threadsStatus", "sourceCreatedAt"]),
    ]
)
@kotlinx.serialization.Serializable
@androidx.room.TypeConverters(ListingStatusConverters::class)
data class ListingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long, val messageId: Long?,
    val adId: String,
    val messageThreadId: Long,
    val sourceRevision: String,
    val title: String = "", val description: String = "",
    val rawText: String = "", val location: String? = null,
    val address: String? = null,
    val typeOfRealty: String? = null,
    val tags: String = "[]",
    val primeParams: String = "{}",
    val secondaryParams: String = "{}",
    val governmentPrograms: String = "[]",
    val googleDriveUrls: String = "[]",
    val price: Long? = null,
    val currency: String? = null,
    val areaM2: Double? = null,
    val landAreaSotka: Double? = null,
    val rooms: Int? = null,
    val floor: Int? = null, val totalFloors: Int? = null,
    val photos: String = "[]",
    val status: ListingStatus = ListingStatus.NeedsReview,
    val sourceCreatedAt: Long, val createdAt: Long,
    val updatedAt: Long,
    val publishedAt: Long? = null,
    val tiktokRepostedAt: Long? = null,
    val threadsRepostedAt: Long? = null,
    val tiktokStatus: String = "PENDING",
    val threadsStatus: String = "PENDING",
    val tiktokState: String = "{\"attempts\":[]}",
    val threadsState: String = "{\"attempts\":[]}",
)
