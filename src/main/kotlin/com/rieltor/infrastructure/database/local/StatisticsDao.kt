package com.rieltor.infrastructure.database.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.rieltor.infrastructure.database.model.ChannelCount
import com.rieltor.infrastructure.database.model.StatisticsEvent

@Dao
internal interface StatisticsDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun record(event: StatisticsEvent)

    @Query("SELECT chatId, messageThreadId, kind, COUNT(*) AS count FROM statisticsEvents WHERE :since IS NULL OR occurredAt >= :since GROUP BY chatId, messageThreadId, kind")
    suspend fun counts(since: Long?): List<ChannelCount>

    @Query("SELECT chatId, messageThreadId, 'ACTIVE' AS kind, COUNT(*) AS count FROM adsTab WHERE status='ACTIVE' GROUP BY chatId, messageThreadId")
    suspend fun active(): List<ChannelCount>

    @Query("SELECT * FROM statisticsEvents WHERE kind IN ('TIKTOK','THREADS') AND (:since IS NULL OR occurredAt >= :since) ORDER BY occurredAt DESC, sourceKey LIMIT :limit OFFSET :offset")
    suspend fun publications(since: Long?, limit: Int, offset: Int): List<StatisticsEvent>

}
