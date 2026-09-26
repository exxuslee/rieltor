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
        SELECT adsTab.*, r.id AS repost_id,
            r.tiktokRepostedAt AS repost_tiktokRepostedAt, r.threadsRepostedAt AS repost_threadsRepostedAt,
            r.tiktokStatus AS repost_tiktokStatus, r.threadsStatus AS repost_threadsStatus,
            r.tiktokState AS repost_tiktokState, r.threadsState AS repost_threadsState
        FROM adsTab JOIN repostTab r ON r.id=adsTab.id
        WHERE status='ACTIVE' AND ((:tiktok AND tiktokStatus='PENDING') OR (:threads AND threadsStatus='PENDING'))
        ORDER BY timestamp DESC, adsTab.id DESC LIMIT 1
    """)
    suspend fun nextRepost(tiktok: Boolean, threads: Boolean): RepostCandidate?

    @Query("SELECT * FROM repostTab WHERE id=:id")
    suspend fun repost(id: Long): RepostEntity?

    @Query("SELECT * FROM repostTab")
    suspend fun reposts(): List<RepostEntity>

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

    @Upsert
    suspend fun persistRepost(row: RepostEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun recordStatistic(event: StatisticsEvent)

    @Transaction
    suspend fun saveRepost(row: RepostEntity) {
        persistRepost(row)
        val ad = listing(row.id) ?: return
        listOf("TIKTOK" to row.tiktokRepostedAt, "THREADS" to row.threadsRepostedAt).forEach { (kind, time) ->
            if (time != null) recordStatistic(StatisticsEvent(kind, ad.adId, ad.chatId, ad.messageId, ad.id, ad.adId, time, ad.messageThreadId))
        }
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun initializeRepost(row: RepostEntity)

    /** Preserve publication history when advertisement content changes. */
    @Transaction
    suspend fun saveListing(row: AdEntity): Long {
        val id = saveAd(row.copy(typeOfRealty = com.rieltor.domain.model.CatalogCodes.normalizeType(row.typeOfRealty))).takeIf { it > 0 } ?: row.id
        initializeRepost(RepostEntity(id))
        return id
    }

    @Query("SELECT " + CatalogListingRow.COLUMNS + " FROM adsTab WHERE id=:id AND status='ACTIVE' AND currency='USD' AND price>0")
    suspend fun publicListing(id: Long): CatalogListingRow?

    @RawQuery
    suspend fun query(query: androidx.room.RoomRawQuery): List<CatalogListingRow>
}
