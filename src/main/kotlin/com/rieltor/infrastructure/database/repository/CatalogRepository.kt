package com.rieltor.infrastructure.database.repository

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.rieltor.domain.model.*
import com.rieltor.infrastructure.database.local.CatalogDao
import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.model.AdEntity
import com.rieltor.infrastructure.database.model.IncomingEntity
import com.rieltor.infrastructure.database.model.IncomingStatus
import com.rieltor.infrastructure.database.model.RepostEntity
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.*

class CatalogRepository(private val database: RoomDatabaseStore) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val json = Json { encodeDefaults = true }

    @Synchronized
    private fun <T> transaction(block: suspend (CatalogDao) -> T): T = database.blocking { room ->
        room.useWriterConnection { it.immediateTransaction { block(room.catalogDao()) } }
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
    fun repost(id: Long) = database.blocking { it.catalogDao().repost(id) }
    fun reposts() = database.blocking { it.catalogDao().reposts() }

    fun cachedPhotos(prepared: AdEntity): List<CatalogPhoto> = transaction { dao ->
        dao.photosForAd(prepared.adId)?.let { json.decodeFromString<List<CatalogPhoto>>(it) }
            ?.distinctBy { it.fileName }.orEmpty()
    }

    fun discard(row: IncomingEntity): Boolean = transaction { dao ->
        val current = dao.source(row.chatId, row.messageId ?: return@transaction false) ?: return@transaction false
        if (revision(current) != revision(row)) return@transaction false
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
        it.catalogDao().nextRepost(tiktok, threads)
    }

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
            dao.saveListing(validate(listingRow))
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
        dao.reposts().forEach { row ->
            var updated = row
            RepostDestination.entries.forEach { destination ->
                val state = publication(updated, destination)
                val attempts = state.attempts.map { attempt ->
                    when (attempt.status) {
                        RepostStatus.Prepared -> attempt.copy(status = RepostStatus.Abandoned, updatedAt = now)
                        RepostStatus.Sending -> attempt.copy(status = RepostStatus.Unknown, updatedAt = now)
                        else -> attempt
                    }
                }
                if (attempts != state.attempts) updated = applyPublication(
                    updated, destination, PublicationState(attempts), now,
                    if (state.attempts.last().status == RepostStatus.Prepared) RepostStatus.Pending else RepostStatus.Unknown
                )
            }
            if (updated != row) dao.saveRepost(updated)
        }
    }

    fun prepare(id: Long, destinations: Set<RepostDestination>, now: Long): String? = transaction { dao ->
        val row = dao.repost(id) ?: return@transaction null
        if (!dao.isActive(id)) return@transaction null
        val attemptId = UUID.randomUUID().toString()
        var updated = row
        destinations.filter { status(row, it) == RepostStatus.Pending }.forEach { destination ->
            val state = publication(updated, destination)
            updated = applyPublication(
                updated, destination, state.copy(
                    attempts = state.attempts +
                            PublishAttempt(attemptId, createdAt = now, updatedAt = now)
                ), now
            )
        }
        if (updated == row) return@transaction null
        dao.saveRepost(updated); attemptId
    }

    fun changeAttempt(
        id: Long, destination: RepostDestination, attemptId: String, now: Long,
        change: (PublishAttempt) -> PublishAttempt
    ) = transaction { dao ->
        val row = dao.repost(id) ?: return@transaction
        val state = publication(row, destination)
        val next =
            state.copy(attempts = state.attempts.map { if (it.attemptId == attemptId) change(it).copy(updatedAt = now) else it })
        dao.saveRepost(applyPublication(row, destination, next, now))
    }

    fun updatePublish(publishId: String, now: Long, change: (PublishAttempt) -> PublishAttempt) = transaction { dao ->
        dao.reposts().forEach { row ->
            val state = publication(row, RepostDestination.TIKTOK)
            if (state.attempts.any { it.publishId == publishId }) {
                val next =
                    state.copy(attempts = state.attempts.map { if (it.publishId == publishId) change(it).copy(updatedAt = now) else it })
                dao.saveRepost(applyPublication(row, RepostDestination.TIKTOK, next, now))
            }
        }
    }

    fun publication(row: RepostEntity, destination: RepostDestination): PublicationState =
        json.decodeFromString(if (destination == RepostDestination.TIKTOK) row.tiktokState else row.threadsState)

    fun status(row: RepostEntity, destination: RepostDestination): RepostStatus =
        RepostStatus.fromCode(if (destination == RepostDestination.TIKTOK) row.tiktokStatus else row.threadsStatus)

    private fun applyPublication(
        row: RepostEntity, destination: RepostDestination, state: PublicationState, now: Long,
        overrideStatus: RepostStatus? = null
    ): RepostEntity {
        val last = state.attempts.lastOrNull()
        val published = state.attempts.any { it.status == RepostStatus.Published }
        val status = (overrideStatus ?: last?.status ?: RepostStatus.Pending).code
        return if (destination == RepostDestination.TIKTOK) row.copy(
            tiktokState = json.encodeToString(state),
            tiktokStatus = status,
            tiktokRepostedAt = row.tiktokRepostedAt ?: now.takeIf { published }
        )
        else row.copy(
            threadsState = json.encodeToString(state),
            threadsStatus = status,
            threadsRepostedAt = row.threadsRepostedAt ?: now.takeIf { published }
        )
    }
}
