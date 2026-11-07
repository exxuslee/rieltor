package com.rieltor.infrastructure.database.local

import androidx.room.*
import com.rieltor.infrastructure.database.model.*

@Dao
internal interface CatalogDao {
    @Query("SELECT * FROM incomeTab")
    suspend fun allSources(): List<IncomingEntity>

    @Query("DELETE FROM incomeTab WHERE chatId=:chat AND messageId IS :message")
    suspend fun deleteSource(chat: Long, message: Long?)

    @Query("DELETE FROM adsTab WHERE adsTab.id=:id")
    suspend fun deleteListing(id: Long)

    @Query("SELECT * FROM incomeTab WHERE chatId=:chat AND messageId=:message")
    suspend fun source(chat: Long, message: Long): IncomingEntity?

    @Query("SELECT * FROM incomeTab WHERE messageId IS NOT NULL AND status NOT IN ('DELETED','NEEDS_REVIEW') ORDER BY sourceCreatedAt DESC, id DESC")
    suspend fun sources(): List<IncomingEntity>

    @Upsert
    suspend fun saveSource(row: IncomingEntity): Long

    @Query("SELECT * FROM adsTab WHERE id=:id")
    suspend fun listing(id: Long): AdEntity?

    @Query("SELECT * FROM adsTab WHERE chatId=:chat AND messageId IS :message")
    suspend fun listingForSource(chat: Long, message: Long?): AdEntity?

    @Query("SELECT * FROM adsTab ORDER BY timestamp DESC, id DESC")
    suspend fun listings(): List<AdEntity>

    @Query("""
        SELECT * FROM adsTab
        WHERE chatId=:chatId AND status='ACTIVE'
            AND NOT EXISTS (SELECT 1 FROM incomeTab i
                WHERE i.chatId=adsTab.chatId AND i.messageId IS adsTab.messageId AND i.isAllowRepost=0)
            AND ((:tiktok AND repostTikTok='PENDING') OR (:threads AND repostThreads='PENDING'))
        ORDER BY timestamp DESC, id DESC LIMIT 1
    """)
    suspend fun nextRepost(tiktok: Boolean, threads: Boolean, chatId: Long): AdEntity?

    @Query("UPDATE adsTab SET repostTikTok=:status WHERE id=:id")
    suspend fun updateTikTok(id: Long, status: String)

    @Query("UPDATE adsTab SET repostThreads=:status WHERE id=:id")
    suspend fun updateThreads(id: Long, status: String)

    @Query("SELECT EXISTS(SELECT 1 FROM adsTab WHERE id=:id AND status='ACTIVE')")
    suspend fun isActive(id: Long): Boolean

    @Query("SELECT photos FROM adsTab WHERE adId=:adId")
    suspend fun photosForAd(adId: String): String?

    @Query("SELECT * FROM adsTab WHERE (chatId=:chat AND messageId IS :message) OR adId=:adId")
    suspend fun promotionMatches(chat: Long, message: Long?, adId: String): List<AdEntity>

    @Query("SELECT COUNT(*) FROM adsTab")
    suspend fun count(): Int

    @Upsert
    suspend fun saveAd(row: AdEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun recordStatistic(event: StatisticsEvent)

    /** Preserve publication history when advertisement content changes. */
    @Transaction
    suspend fun saveListing(row: AdEntity): Long {
        val previous = if (row.id > 0) listing(row.id) else null
        val preserved = row.copy(
            repostTikTok = previous?.repostTikTok ?: row.repostTikTok,
            repostThreads = previous?.repostThreads ?: row.repostThreads,
        )
        val id = saveAd(preserved.copy(typeOfRealty = com.rieltor.domain.model.CatalogCodes.normalizeType(row.typeOfRealty))).takeIf { it > 0 } ?: row.id
        return id
    }

    @Query("SELECT " + CatalogListingRow.COLUMNS + " FROM adsTab WHERE id=:id AND status='ACTIVE' AND currency='USD' AND price>0")
    suspend fun publicListing(id: Long): CatalogListingRow?

    @RawQuery
    suspend fun query(query: androidx.room.RoomRawQuery): List<CatalogListingRow>
}
