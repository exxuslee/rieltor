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
    @Query("SELECT * FROM incomeTab")
    suspend fun allSources(): List<IncomingEntity>

    @Query("DELETE FROM incomeTab WHERE chatId=:chat AND messageId IS :message")
    suspend fun deleteSource(chat: Long, message: Long?)

    @Query("DELETE FROM adsTab WHERE id=:id")
    suspend fun deleteListing(id: Long)

    @Query("SELECT * FROM incomeTab WHERE chatId=:chat AND messageId=:message")
    suspend fun source(chat: Long, message: Long): IncomingEntity?

    @Query("SELECT * FROM incomeTab WHERE messageId IS NOT NULL AND status NOT IN ('DELETED','NEEDS_REVIEW') ORDER BY sourceCreatedAt DESC, id DESC")
    suspend fun sources(): List<IncomingEntity>

    @Upsert
    suspend fun saveSource(row: IncomingEntity): Long

    @Query("SELECT * FROM adsTab WHERE id=:id")
    suspend fun listing(id: Long): ListingEntity?

    @Query("SELECT * FROM adsTab WHERE chatId=:chat AND messageId IS :message")
    suspend fun listingForSource(chat: Long, message: Long?): ListingEntity?

    @Query("SELECT * FROM adsTab ORDER BY sourceCreatedAt DESC, id DESC")
    suspend fun listings(): List<ListingEntity>

    @Query("SELECT * FROM adsTab WHERE status='ACTIVE' AND ((:tiktok AND tiktokStatus='PENDING') OR (:threads AND threadsStatus='PENDING')) ORDER BY sourceCreatedAt DESC, id DESC LIMIT 1")
    suspend fun nextRepost(tiktok: Boolean, threads: Boolean): ListingEntity?

    @Query("SELECT COUNT(*) FROM adsTab")
    suspend fun count(): Int

    @Upsert
    suspend fun saveListing(row: ListingEntity): Long

    @Query("SELECT " + CatalogListingRow.COLUMNS + " FROM adsTab WHERE id=:id AND status='ACTIVE' AND currency='USD' AND price>0")
    suspend fun publicListing(id: Long): CatalogListingRow?

    @RawQuery
    suspend fun query(query: androidx.room.RoomRawQuery): List<CatalogListingRow>
}