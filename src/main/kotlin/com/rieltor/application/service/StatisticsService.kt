package com.rieltor.application.service

import androidx.room.deferredTransaction
import androidx.room.useReaderConnection
import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

@Serializable
data class StatisticsCounts(
    val received: Long = 0, val processed: Long = 0, val accepted: Long = 0,
    val tiktok: Long = 0, val threads: Long = 0,
)
@Serializable
data class ChannelStatistics(
    val chatId: String, val name: String, val active: Long,
    val day: StatisticsCounts, val week: StatisticsCounts, val total: StatisticsCounts,
    val topics: List<TopicStatistics>,
)
@Serializable
data class TopicStatistics(
    val messageThreadId: String?, val name: String, val active: Long,
    val day: StatisticsCounts, val week: StatisticsCounts, val total: StatisticsCounts,
)
@Serializable
data class PublishedStatistic(
    val platform: String, val chatId: String, val messageId: String?,
    val listingId: String?, val adId: String?, val publishedAt: Long?,
    val messageThreadId: String?, val topicName: String,
)
@Serializable
data class HealthStatistics(
    val status: String = "ok", val timezone: String = "Europe/Kyiv", val generatedAt: Long,
    val channels: List<ChannelStatistics>,
    val publications: List<PublishedStatistic>, val publicationPeriod: String,
    val publicationOffset: Int, val hasMorePublications: Boolean,
    val historicalNote: String = "Історичні часи отримання й прийняття невідомі: ці записи враховані лише у підсумку. Видалені до запуску статистики записи відновити неможливо. Якщо підгрупу старого запису неможливо відновити, він відображається як «Підгрупа невідома».",
)

class StatisticsService(private val database: RoomDatabaseStore, private val clock: Clock = Clock.systemUTC()) {
    private val zone = ZoneId.of("Europe/Kyiv")

    suspend fun snapshot(period: String = "day", offset: Int = 0, limit: Int = 50): HealthStatistics {
        require(period in setOf("day", "week", "total") && offset >= 0 && limit in 1..100)
        val now = clock.instant()
        val today = now.atZone(zone).toLocalDate()
        val week = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val starts = mapOf("day" to today.atStartOfDay(zone).toInstant().toEpochMilli(),
            "week" to week.atStartOfDay(zone).toInstant().toEpochMilli(), "total" to null)
        val settings = database.settings.snapshot()
        val configured = settings.monitoredTelegramChats.associateBy { it.chatId }
        val configuredTopics = settings.topicNames.keys.mapNotNull { key ->
            val parts = key.split(':')
            val chat = parts.getOrNull(0)?.toLongOrNull()
            val topic = parts.getOrNull(1)?.toLongOrNull()
            if (parts.size == 2 && chat != null && topic != null) chat to topic else null
        }.groupBy({ it.first }, { it.second })
        fun topicName(chat: Long, topic: Long?): String = when (topic) {
            null -> "Підгрупа невідома"
            else -> settings.topicNames[chat.toString() + ":" + topic]?.takeIf { it.isNotBlank() }
                ?: if (topic == 0L) "Без підгрупи" else "Підгрупа $topic"
        }
        return database.room.useReaderConnection { connection -> connection.deferredTransaction {
            val dao = database.room.statisticsDao()
            val counts = starts.mapValues { (_, since) -> dao.counts(since).groupBy { it.chatId } }
            val active = dao.active().groupBy { it.chatId }
            val ids = (configured.keys + configuredTopics.keys + counts.getValue("total").keys + active.keys).sorted()
            fun count(chat: Long, periodKey: String, topic: Long? = null, filterTopic: Boolean = false): StatisticsCounts {
                val values = counts.getValue(periodKey)[chat].orEmpty()
                    .filter { !filterTopic || it.messageThreadId == topic }
                    .groupBy { it.kind }.mapValues { (_, rows) -> rows.sumOf { it.count } }
                return StatisticsCounts(values["RECEIVED"] ?: 0, values["PROCESSED"] ?: 0,
                    values["ACCEPTED"] ?: 0, values["TIKTOK"] ?: 0, values["THREADS"] ?: 0)
            }
            val publications = dao.publications(starts[period], limit + 1, offset)
            HealthStatistics(generatedAt = now.toEpochMilli(), channels = ids.map { chat ->
                val topicIds = (configuredTopics[chat].orEmpty() +
                    counts.getValue("total")[chat].orEmpty().map { it.messageThreadId } +
                    active[chat].orEmpty().map { it.messageThreadId }).distinct().sortedWith(nullsLast())
                val topics = topicIds.map { topic -> TopicStatistics(topic?.toString(), topicName(chat, topic),
                    active[chat].orEmpty().filter { it.messageThreadId == topic }.sumOf { it.count },
                    count(chat, "day", topic, true), count(chat, "week", topic, true), count(chat, "total", topic, true)) }
                ChannelStatistics(chat.toString(), configured[chat]?.name?.takeIf { it.isNotBlank() } ?: "Канал $chat",
                    active[chat].orEmpty().sumOf { it.count }, count(chat, "day"), count(chat, "week"), count(chat, "total"), topics)
            },
                publications = publications.take(limit).map { PublishedStatistic(it.kind, it.chatId.toString(),
                    it.messageId?.toString(), it.listingId?.toString(), it.adId, it.occurredAt,
                    it.messageThreadId?.toString(), topicName(it.chatId, it.messageThreadId)) },
                publicationPeriod = period, publicationOffset = offset, hasMorePublications = publications.size > limit)
        } }
    }
}
