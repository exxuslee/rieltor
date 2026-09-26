package com.rieltor.infrastructure.database.model

import androidx.room.Entity
import androidx.room.Index

/** No foreign keys: historical counters must survive catalog retention. */
@Entity(tableName = "statisticsEvents", primaryKeys = ["kind", "sourceKey"], indices = [Index("occurredAt")])
data class StatisticsEvent(
    val kind: String,
    val sourceKey: String,
    val chatId: Long,
    val messageId: Long?,
    val listingId: Long? = null,
    val adId: String? = null,
    val occurredAt: Long?,
)

@Entity(tableName = "siteVisitors", primaryKeys = ["day", "visitorId"])
data class SiteVisitor(val day: String, val visitorId: String)

data class ChannelCount(val chatId: Long, val kind: String, val count: Long)
