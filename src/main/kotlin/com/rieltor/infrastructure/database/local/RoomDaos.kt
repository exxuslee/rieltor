package com.rieltor.infrastructure.database.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import com.rieltor.infrastructure.database.model.CatalogListingRow
import com.rieltor.infrastructure.database.model.IncomingEntity
import com.rieltor.infrastructure.database.model.ListingEntity

@Dao
internal interface CatalogDao {
    @Query("SELECT * FROM incoming_telegram_messages")
    suspend fun allSources(): List<IncomingEntity>
    @Query("DELETE FROM incoming_telegram_messages WHERE groupKey=:key")
    suspend fun deleteGroup(key: String)
    @Query("DELETE FROM listings WHERE id=:id")
    suspend fun deleteListing(id: Long)

    @Query("SELECT * FROM incoming_telegram_messages WHERE chatId=:chat AND messageId=:message")
    suspend fun source(chat: Long, message: Long): IncomingEntity?

    @Query("SELECT * FROM incoming_telegram_messages WHERE groupKey=:key ORDER BY messageId")
    suspend fun group(key: String): List<IncomingEntity>

    @Query("SELECT * FROM incoming_telegram_messages WHERE messageId IS NOT NULL AND status NOT IN ('DELETED','NEEDS_REVIEW') ORDER BY sourceCreatedAt DESC, id DESC")
    suspend fun sources(): List<IncomingEntity>
    @Upsert
    suspend fun saveSource(row: IncomingEntity): Long
    @Query("SELECT * FROM listings WHERE id=:id")
    suspend fun listing(id: Long): ListingEntity?
    @Query("SELECT * FROM listings WHERE groupKey=:key")
    suspend fun listingForGroup(key: String): ListingEntity?
    @Query("SELECT * FROM listings ORDER BY sourceCreatedAt DESC, id DESC")
    suspend fun listings(): List<ListingEntity>

    @Query("SELECT * FROM listings WHERE status='ACTIVE' AND ((:tiktok AND tiktokStatus='PENDING') OR (:threads AND threadsStatus='PENDING')) ORDER BY sourceCreatedAt DESC, id DESC LIMIT 1")
    suspend fun nextRepost(tiktok: Boolean, threads: Boolean): ListingEntity?
    @Query("SELECT COUNT(*) FROM listings")
    suspend fun count(): Int
    @Upsert
    suspend fun saveListing(row: ListingEntity): Long

    @Query("SELECT " + CatalogListingRow.COLUMNS + " FROM listings WHERE id=:id AND status='ACTIVE' AND currency='USD' AND price>0")
    suspend fun publicListing(id: Long): CatalogListingRow?
    @RawQuery
    suspend fun query(query: androidx.room.RoomRawQuery): List<CatalogListingRow>
}
