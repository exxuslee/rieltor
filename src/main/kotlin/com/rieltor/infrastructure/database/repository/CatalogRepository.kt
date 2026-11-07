package com.rieltor.infrastructure.database.repository

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.rieltor.domain.model.*
import com.rieltor.infrastructure.database.local.CatalogDao
import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.model.AdEntity
import com.rieltor.infrastructure.database.model.IncomingEntity
import com.rieltor.infrastructure.database.model.IncomingStatus
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class CatalogRepository(private val database: RoomDatabaseStore) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val json = Json { encodeDefaults = true }

    @Synchronized
    private fun <T> transaction(block: suspend (CatalogDao) -> T): T = database.blocking { room ->
        room.useWriterConnection { it.immediateTransaction { block(room.catalogDao()) } }
    }

    private suspend fun record(kind: String, row: IncomingEntity, now: Long, listingId: Long? = null, adId: String? = null) {
        database.room.statisticsDao().record(com.rieltor.infrastructure.database.model.StatisticsEvent(
            kind, row.chatId.toString() + ":" + row.messageId, row.chatId, row.messageId, listingId, adId, now, row.messageThreadId
        ))
    }

    fun receive(message: SourceMessage, now: Long, stabilityMs: Long) = transaction { dao ->
        val old = dao.source(message.chatId, message.messageId)
        if (old?.contentHash == message.fingerprint() || old?.status == IncomingStatus.Deleted) {
            return@transaction
        }
        if (old != null && message.sourceEditedAt < old.sourceEditedAt) {
            return@transaction
        }
        val row = IncomingEntity(
            id = old?.id ?: 0,
            chatId = message.chatId,
            messageId = message.messageId,
            messageThreadId = message.messageThreadId,
            rawText = message.text,
            userId = message.userId ?: old?.userId ?: userIdFromRaw(message.raw),
            sourceCreatedAt = message.sourceCreatedAt,
            sourceEditedAt = message.sourceEditedAt,
            contentHash = message.fingerprint(),
            revision = (old?.revision ?: 0) + 1,
            verifyAfter = now + stabilityMs,
            // A caption edit arrives without media; the already known album must survive it.
            sourcePhotos = if (message.photos.isEmpty()) old?.sourcePhotos ?: "[]"
            else json.encodeToString(message.photos),
        )
        dao.saveSource(row)
        record("RECEIVED", row, now)
        dao.listingForSource(message.chatId, message.messageId)
            ?.let { dao.saveAd(it.copy(status = ListingStatus.Hidden)) }
    }

    fun delete(chatId: Long, messageId: Long, now: Long) = transaction { dao ->
        val row = dao.source(chatId, messageId) ?: return@transaction
        dao.saveSource(row.copy(status = IncomingStatus.Deleted))
        dao.listingForSource(row.chatId, row.messageId)
            ?.let { dao.saveAd(it.copy(status = ListingStatus.Hidden)) }
    }

    fun source(chatId: Long, messageId: Long) = transaction { it.source(chatId, messageId) }
    fun incoming(): List<IncomingEntity> = transaction { it.sources() }

    fun publicListing(id: Long) = database.blocking { it.catalogDao().publicListing(id) }
    fun listing(id: Long) = database.blocking { it.catalogDao().listing(id) }
    fun listings() = database.blocking { it.catalogDao().listings() }
    fun cachedPhotos(prepared: AdEntity): List<CatalogPhoto> = transaction { dao ->
        dao.photosForAd(prepared.adId)?.let { json.decodeFromString<List<CatalogPhoto>>(it) }
            ?.distinctBy { it.fileName }.orEmpty()
    }

    fun discard(row: IncomingEntity): Boolean = transaction { dao ->
        val current = dao.source(row.chatId, row.messageId ?: return@transaction false) ?: return@transaction false
        if (revision(current) != revision(row)) return@transaction false
        record("PROCESSED", current, System.currentTimeMillis())
        dao.listingForSource(row.chatId, row.messageId)?.let { dao.deleteListing(it.id) }
        dao.deleteSource(row.chatId, row.messageId)
        true
    }

    data class MediaRetention(val referenced: Set<String>, val removed: Set<String>)

    fun <T> withMediaCleanup(cutoff: Long, sweep: (MediaRetention) -> T): T = synchronized(this) {
        // Serialize the snapshot + file sweep with claims, manifests and promotions.
        sweep(cleanExpired(cutoff))
    }

    /** Source dates only: verification, retries and social publication never extend retention. */
    fun cleanExpired(cutoff: Long): MediaRetention = transaction { dao ->
        val sources = dao.allSources()
        val listings = dao.listings()
        fun files(sources: List<IncomingEntity>, listings: List<AdEntity>) =
            (sources.flatMap { json.decodeFromString<List<CatalogPhoto>>(it.mediaManifest) } +
                    listings.flatMap { json.decodeFromString<List<CatalogPhoto>>(it.photos) }).map { it.fileName }
                .toSet()

        val before = files(sources, listings)
        val expired = listings.filter { listing ->
            maxOf(
                listing.timestamp,
                sources.filter { (it.chatId == listing.chatId && it.messageId == listing.messageId) }
                    .maxOfOrNull { maxOf(it.sourceCreatedAt, it.sourceEditedAt) } ?: 0) < cutoff
        }
        expired.forEach { dao.deleteListing(it.id) }
        sources.forEach { row ->
            if (maxOf(row.sourceCreatedAt, row.sourceEditedAt) < cutoff ||
                row.status in setOf(IncomingStatus.Deleted, IncomingStatus.NeedsReview)
            ) {
                dao.deleteSource(row.chatId, row.messageId)
                dao.listingForSource(row.chatId, row.messageId)?.takeIf { it.status != ListingStatus.Active }
                    ?.let { dao.deleteListing(it.id) }
            }
        }
        val remaining = files(dao.allSources(), dao.listings())
        MediaRetention(remaining, before - remaining)
    }

    private fun validate(row: AdEntity): AdEntity {
        require(row.status != ListingStatus.Active || (row.currency == "USD" && (row.price ?: 0) > 0)) {
            "Active listings require a positive total price in USD"
        }
        return row
    }

    fun nextRepost(tiktok: Boolean, threads: Boolean) = database.blocking {
        it.catalogDao().nextRepost(tiktok, threads, REPOST_SOURCE_CHAT_ID)
    }

    private suspend fun isAllowRepost(dao: CatalogDao, id: Long): Boolean {
        val row = dao.listing(id) ?: return false
        val source = row.messageId?.let { dao.source(row.chatId, it) }
        return source?.isAllowRepost != false
    }

    fun isAllowRepost(id: Long): Boolean = transaction { isAllowRepost(it, id) }

    fun save(row: AdEntity): Long =
        transaction { it.saveListing(validate(row)).takeIf { id -> id > 0 } ?: row.id }

    fun query(query: androidx.room.RoomRawQuery) = database.blocking { it.catalogDao().query(query) }

    fun revision(row: IncomingEntity) = sha256("${row.id}:${row.revision}:${row.contentHash}")

    fun stage(
        row: IncomingEntity,
        status: IncomingStatus,
        now: Long,
        next: Long = 0,
        failed: Boolean = false
    ): Boolean =
        transaction { dao ->
            val current = dao.source(row.chatId, row.messageId ?: return@transaction false) ?: return@transaction false
            if (revision(current) != revision(row) || current.status == IncomingStatus.Deleted) return@transaction false
            if (status == IncomingStatus.NeedsReview) record("PROCESSED", current, now)
            dao.saveSource(
                current.copy(
                    status = status,
                    verifiedAt = if (status in setOf(
                            IncomingStatus.ReadyForMedia,
                            IncomingStatus.Promoted
                        )
                    ) now else current.verifiedAt,
                    nextAttemptAt = next,
                    attemptCount = if (failed) current.attemptCount + 1 else current.attemptCount
                )
            )
            true
        }

    /** Moves an eligible row to Downloading; a second claim of the same row fails on the status check. */
    fun claim(row: IncomingEntity, now: Long): Boolean = transaction { dao ->
        val current = dao.source(row.chatId, row.messageId ?: return@transaction false) ?: return@transaction false
        if (revision(current) != revision(row) ||
            current.status !in setOf(
                IncomingStatus.ReadyForMedia,
                IncomingStatus.MediaRetry,
                IncomingStatus.MediaReady
            ) ||
            current.nextAttemptAt > now
        ) return@transaction false
        dao.saveSource(current.copy(status = IncomingStatus.Downloading))
        true
    }

    fun manifest(row: IncomingEntity, photos: List<CatalogPhoto>): Boolean =
        transaction { dao ->
            val current = dao.source(row.chatId, row.messageId ?: return@transaction false) ?: return@transaction false
            if (revision(current) != revision(row) || current.status != IncomingStatus.Downloading) return@transaction false
            dao.saveSource(current.copy(mediaManifest = json.encodeToString(photos)))
            true
        }

    fun promote(row: IncomingEntity, prepared: AdEntity, now: Long): Boolean =
        transaction { dao ->
            val current = dao.source(row.chatId, row.messageId ?: return@transaction false) ?: return@transaction false
            if (revision(current) != revision(row) || current.status != IncomingStatus.Downloading) {
                return@transaction false
            }
            record("PROCESSED", current, now)
            // Same source is an edit; different posts are duplicates only when their adId matches.
            val matches = dao.promotionMatches(prepared.chatId, prepared.messageId, prepared.adId)
            val old = matches.maxByOrNull { it.timestamp }
            // An old queued/replayed message must not overwrite a newer price or extend its lifetime.
            if (old != null && old.timestamp > prepared.timestamp) {
                if (old.chatId != prepared.chatId || old.messageId != prepared.messageId) {
                    dao.deleteSource(prepared.chatId, prepared.messageId)
                }
                return@transaction false
            }
            matches.filter { it.id != old?.id }.forEach { dao.deleteListing(it.id) }
            matches.filter { (it.chatId != prepared.chatId || it.messageId != prepared.messageId) }
                .forEach { dao.deleteSource(it.chatId, it.messageId) }
            val listingRow = if (old == null) prepared else prepared.copy(
                id = old.id,
            )
            val acceptedId = dao.saveListing(validate(listingRow))
            record("ACCEPTED", current, now, acceptedId, listingRow.adId)
            dao.saveSource(current.copy(status = IncomingStatus.Promoted))
            true
        }

    fun recover(now: Long) = transaction { dao ->
        dao.sources().filter { it.status == IncomingStatus.Downloading }.forEach {
            dao.saveSource(
                it.copy(
                    status = IncomingStatus.MediaRetry,
                    nextAttemptAt = now
                )
            )
        }
    }

    /** Atomically claim a destination; non-pending results stay out of the automatic queue. */
    fun beginRepost(id: Long, destination: RepostDestination, manual: Boolean = false): Boolean = transaction { dao ->
        val row = dao.listing(id) ?: return@transaction false
        if (row.status != ListingStatus.Active || row.chatId != REPOST_SOURCE_CHAT_ID || !isAllowRepost(dao, id)) {
            return@transaction false
        }
        val current = if (destination == RepostDestination.TIKTOK) row.repostTikTok else row.repostThreads
        if (current == "SENDING" || (!manual && current != "PENDING")) return@transaction false
        if (destination == RepostDestination.TIKTOK) dao.updateTikTok(id, "SENDING")
        else dao.updateThreads(id, "SENDING")
        true
    }

    fun finishRepost(id: Long, destination: RepostDestination, status: RepostStatus, now: Long) = transaction { dao ->
        val row = dao.listing(id) ?: return@transaction
        if (destination == RepostDestination.TIKTOK) dao.updateTikTok(id, status.code)
        else dao.updateThreads(id, status.code)
        if (status in setOf(RepostStatus.Published, RepostStatus.DeliveredDraft)) {
            dao.recordStatistic(com.rieltor.infrastructure.database.model.StatisticsEvent(
                destination.name, row.adId, row.chatId, row.messageId, row.id, row.adId, now, row.messageThreadId,
            ))
        }
    }
}
