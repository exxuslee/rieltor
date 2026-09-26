package com.rieltor.application.service

import androidx.room.deferredTransaction
import androidx.room.useReaderConnection
import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.model.SiteVisitor
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.ZoneId

@Serializable
data class StatisticsCounts(
    val received: Long = 0, val processed: Long = 0, val accepted: Long = 0,
    val tiktok: Long = 0, val threads: Long = 0,
)
@Serializable
data class ChannelStatistics(
    val chatId: String, val name: String, val active: Long,
    val day: StatisticsCounts, val month: StatisticsCounts, val total: StatisticsCounts,
)
@Serializable
data class VisitorStatistics(val day: Long, val month: Long, val total: Long)
@Serializable
data class PublishedStatistic(
    val platform: String, val chatId: String, val messageId: String?,
    val listingId: String?, val adId: String?, val publishedAt: Long?,
)
@Serializable
data class HealthStatistics(
    val status: String = "ok", val timezone: String = "Europe/Kyiv", val generatedAt: Long,
    val channels: List<ChannelStatistics>, val visitors: VisitorStatistics,
    val publications: List<PublishedStatistic>, val publicationPeriod: String,
    val publicationOffset: Int, val hasMorePublications: Boolean,
    val historicalNote: String = "Історичні часи отримання й прийняття невідомі: ці записи враховані лише у підсумку. Видалені до запуску статистики записи відновити неможливо.",
)

class StatisticsService(private val database: RoomDatabaseStore, private val clock: Clock = Clock.systemUTC()) {
    private val zone = ZoneId.of("Europe/Kyiv")

    suspend fun visit(visitorId: String) {
        database.room.statisticsDao().visit(SiteVisitor(clock.instant().atZone(zone).toLocalDate().toString(), visitorId))
    }

    suspend fun snapshot(period: String = "day", offset: Int = 0, limit: Int = 50): HealthStatistics {
        require(period in setOf("day", "month", "total") && offset >= 0 && limit in 1..100)
        val now = clock.instant()
        val today = now.atZone(zone).toLocalDate()
        val month = today.withDayOfMonth(1)
        val starts = mapOf("day" to today.atStartOfDay(zone).toInstant().toEpochMilli(),
            "month" to month.atStartOfDay(zone).toInstant().toEpochMilli(), "total" to null)
        val configured = database.settings.snapshot().monitoredTelegramChats.associateBy { it.chatId }
        return database.room.useReaderConnection { connection -> connection.deferredTransaction {
            val dao = database.room.statisticsDao()
            val counts = starts.mapValues { (_, since) -> dao.counts(since).groupBy { it.chatId } }
            val active = dao.active().associate { it.chatId to it.count }
            val ids = (configured.keys + counts.getValue("total").keys + active.keys).sorted()
            fun count(chat: Long, periodKey: String): StatisticsCounts {
                val values = counts.getValue(periodKey)[chat].orEmpty().associate { it.kind to it.count }
                return StatisticsCounts(values["RECEIVED"] ?: 0, values["PROCESSED"] ?: 0,
                    values["ACCEPTED"] ?: 0, values["TIKTOK"] ?: 0, values["THREADS"] ?: 0)
            }
            val publications = dao.publications(starts[period], limit + 1, offset)
            HealthStatistics(generatedAt = now.toEpochMilli(), channels = ids.map { chat ->
                ChannelStatistics(chat.toString(), configured[chat]?.name?.takeIf { it.isNotBlank() } ?: "Канал $chat",
                    active[chat] ?: 0, count(chat, "day"), count(chat, "month"), count(chat, "total"))
            }, visitors = VisitorStatistics(dao.visitors(today.toString()), dao.visitors(month.toString()), dao.visitors(null)),
                publications = publications.take(limit).map { PublishedStatistic(it.kind, it.chatId.toString(),
                    it.messageId?.toString(), it.listingId?.toString(), it.adId, it.occurredAt) },
                publicationPeriod = period, publicationOffset = offset, hasMorePublications = publications.size > limit)
        } }
    }
}
