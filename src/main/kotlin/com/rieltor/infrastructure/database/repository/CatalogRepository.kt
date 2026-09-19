package com.rieltor.infrastructure.database.repository

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.rieltor.domain.model.*
import com.rieltor.infrastructure.database.local.CatalogDao
import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.model.IncomingEntity
import com.rieltor.infrastructure.database.model.ListingEntity
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
        if (old?.contentHash == message.fingerprint() || old?.status == "DELETED") {
            return@transaction
        }
        if (old != null && message.sourceEditedAt < old.sourceEditedAt) {
            return@transaction
        }
        val row = IncomingEntity(
            id = old?.id ?: 0, chatId = message.chatId, messageId = message.messageId,
            messageThreadId = message.messageThreadId,
            rawMessage = message.raw, rawText = message.text,
            userId = message.userId ?: old?.userId ?: userIdFromRaw(message.raw),
            sourceCreatedAt = message.sourceCreatedAt, sourceEditedAt = message.sourceEditedAt,
            receivedAt = old?.receivedAt ?: now, contentHash = message.fingerprint(),
            revision = (old?.revision ?: 0) + 1, verifyAfter = now + stabilityMs,
        )
        dao.saveSource(row)
        // An edit invalidates the source's current media lease.
        dao.group(message.chatId, message.messageId).filter { it.status != "DELETED" }.forEach {
            dao.saveSource(
                it.copy(
                    status = "WAITING_STABILITY", verifyAfter = now + stabilityMs,
                    verifiedAt = null, leaseToken = null, leaseUntil = 0, attemptCount = 0, nextAttemptAt = 0
                )
            )
        }
        dao.listingForSource(message.chatId, message.messageId)?.let { dao.saveListing(it.copy(status = "HIDDEN", updatedAt = now)) }
    }

    fun delete(chatId: Long, messageId: Long, now: Long) = transaction { dao ->
        val row = dao.source(chatId, messageId) ?: return@transaction
        dao.group(row.chatId, row.messageId)
            .forEach { dao.saveSource(it.copy(status = "DELETED", leaseToken = null, leaseUntil = 0)) }
        dao.listingForSource(row.chatId, row.messageId)?.let { dao.saveListing(it.copy(status = "HIDDEN", updatedAt = now)) }
    }

    fun source(chatId: Long, messageId: Long) = transaction { it.source(chatId, messageId) }
    fun group(chatId: Long, messageId: Long?) = transaction { it.group(chatId, messageId) }
    fun groups() =
        transaction { it.sources() }.groupBy { it.chatId to it.messageId }.values.map { it.sortedBy { row -> row.messageId } }

    fun publicListing(id: Long) = database.blocking { it.catalogDao().publicListing(id) }
    fun listing(id: Long) = transaction { it.listing(id) }
    fun listings() = transaction { it.listings() }

    fun cachedPhotos(prepared: ListingEntity): List<CatalogPhoto> = transaction { dao ->
        dao.listings().filter { it.adId == prepared.adId }
            .flatMap { json.decodeFromString<List<CatalogPhoto>>(it.photos) }.distinctBy { it.fileName }
    }

    fun discard(rows: List<IncomingEntity>): Boolean = transaction { dao ->
        if (revision(dao.group(rows.first().chatId, rows.first().messageId)) != revision(rows)) return@transaction false
        dao.listingForSource(rows.first().chatId, rows.first().messageId)?.let { dao.deleteListing(it.id) }
        dao.deleteGroup(rows.first().chatId, rows.first().messageId)
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
        fun files(sources: List<IncomingEntity>, listings: List<ListingEntity>) =
            (sources.flatMap { json.decodeFromString<List<CatalogPhoto>>(it.mediaManifest) } +
                    listings.flatMap { json.decodeFromString<List<CatalogPhoto>>(it.photos) }).map { it.fileName }
                .toSet()

        val before = files(sources, listings)
        val expired = listings.filter { listing ->
            maxOf(listing.sourceCreatedAt, sources.filter { (it.chatId == listing.chatId && it.messageId == listing.messageId) }
                .maxOfOrNull { maxOf(it.sourceCreatedAt, it.sourceEditedAt) } ?: 0) < cutoff
        }
        expired.forEach { dao.deleteListing(it.id) }
        sources.groupBy { it.chatId to it.messageId }.forEach { (key, rows) ->
            if (rows.maxOf { maxOf(it.sourceCreatedAt, it.sourceEditedAt) } < cutoff ||
                rows.all { it.status in setOf("DELETED", "NEEDS_REVIEW") }) {
                dao.deleteGroup(key.first, key.second)
                dao.listingForSource(key.first, key.second)?.takeIf { it.status != "ACTIVE" }?.let { dao.deleteListing(it.id) }
            }
        }
        val remaining = files(dao.allSources(), dao.listings())
        MediaRetention(remaining, before - remaining)
    }

    private fun validate(row: ListingEntity): ListingEntity {
        require(row.status != "ACTIVE" || (row.currency == "USD" && (row.price ?: 0) > 0)) {
            "Active listings require a positive total price in USD"
        }
        return row
    }

    fun nextRepost(tiktok: Boolean, threads: Boolean) = database.blocking {
        it.catalogDao().nextRepost(tiktok, threads)
    }

    fun save(row: ListingEntity): Long =
        transaction { it.saveListing(validate(row)).takeIf { id -> id > 0 } ?: row.id }

    fun query(query: androidx.room.RoomRawQuery) = database.blocking { it.catalogDao().query(query) }

    fun revision(rows: List<IncomingEntity>) =
        sha256(rows.joinToString("|") { "${it.id}:${it.revision}:${it.contentHash}" })

    fun stage(rows: List<IncomingEntity>, status: String, now: Long, next: Long = 0, failed: Boolean = false): Boolean =
        transaction { dao ->
            val current = dao.group(rows.first().chatId, rows.first().messageId)
            if (revision(current) != revision(rows) || current.any { it.status == "DELETED" }) return@transaction false
            current.forEach {
                dao.saveSource(
                    it.copy(
                        status = status,
                        verifiedAt = if (status in setOf("READY_FOR_MEDIA", "PROMOTED")) now else it.verifiedAt,
                        nextAttemptAt = next, leaseToken = null, leaseUntil = 0,
                        attemptCount = if (failed) it.attemptCount + 1 else it.attemptCount
                    )
                )
            }
            true
        }

    fun claim(rows: List<IncomingEntity>, now: Long): String? = transaction { dao ->
        val current = dao.group(rows.first().chatId, rows.first().messageId)
        if (revision(current) != revision(rows) || current.any {
                it.status !in setOf(
                    "READY_FOR_MEDIA",
                    "MEDIA_RETRY",
                    "MEDIA_READY"
                ) || it.nextAttemptAt > now || it.leaseUntil > now
            }) return@transaction null
        val token = UUID.randomUUID().toString()
        current.forEach {
            dao.saveSource(
                it.copy(
                    status = "DOWNLOADING",
                    leaseToken = token,
                    leaseUntil = now + 120_000
                )
            )
        }
        token
    }

    fun manifest(rows: List<IncomingEntity>, token: String, photos: List<CatalogPhoto>, now: Long): Boolean =
        transaction { dao ->
            val current = dao.group(rows.first().chatId, rows.first().messageId)
            if (revision(current) != revision(rows) || current.any { it.leaseToken != token }) return@transaction false
            current.forEach {
                dao.saveSource(
                    it.copy(
                        mediaManifest = json.encodeToString(photos),
                        leaseUntil = now + 120_000
                    )
                )
            }
            true
        }

    fun promote(rows: List<IncomingEntity>, token: String, prepared: ListingEntity, now: Long): Boolean =
        transaction { dao ->
            val current = dao.group(rows.first().chatId, rows.first().messageId)
            if (revision(current) != revision(rows) || current.any { it.leaseToken != token || it.status != "DOWNLOADING" }) return@transaction false
            // Same source is an edit; different posts are duplicates only when their adId matches.
            val matches = dao.listings().filter { (it.chatId == prepared.chatId && it.messageId == prepared.messageId) || it.adId == prepared.adId }
            val old = matches.maxByOrNull { it.sourceCreatedAt }
            // An old queued/replayed message must not overwrite a newer price or extend its lifetime.
            if (old != null && old.sourceCreatedAt > prepared.sourceCreatedAt) {
                if ((old.chatId != prepared.chatId || old.messageId != prepared.messageId)) dao.deleteGroup(prepared.chatId, prepared.messageId)
                return@transaction false
            }
            matches.filter { it.id != old?.id }.forEach { dao.deleteListing(it.id) }
            matches.filter { (it.chatId != prepared.chatId || it.messageId != prepared.messageId) }.forEach { dao.deleteGroup(it.chatId, it.messageId) }
            val row = if (old == null) prepared else prepared.copy(
                id = old.id, createdAt = old.createdAt, publishedAt = old.publishedAt ?: now,
                tiktokRepostedAt = old.tiktokRepostedAt, threadsRepostedAt = old.threadsRepostedAt,
                tiktokStatus = old.tiktokStatus, threadsStatus = old.threadsStatus,
                tiktokState = old.tiktokState, threadsState = old.threadsState
            )
            val id = dao.saveListing(validate(row)).takeIf { it > 0 } ?: row.id
            current.forEach { dao.saveSource(it.copy(status = "PROMOTED", leaseToken = null, leaseUntil = 0)) }
            true
        }

    fun recover(now: Long) = transaction { dao ->
        dao.sources().filter { it.status == "DOWNLOADING" }.forEach {
            dao.saveSource(it.copy(status = "MEDIA_RETRY", leaseToken = null, leaseUntil = 0, nextAttemptAt = now))
        }
        dao.listings().forEach { row ->
            var updated = row
            RepostDestination.entries.forEach { destination ->
                val state = publication(updated, destination)
                val attempts = state.attempts.map { attempt ->
                    when (attempt.status) {
                        "PREPARED" -> attempt.copy(status = "ABANDONED", updatedAt = now)
                        "SENDING" -> attempt.copy(status = "UNKNOWN", updatedAt = now)
                        else -> attempt
                    }
                }
                if (attempts != state.attempts) updated = applyPublication(
                    updated, destination, PublicationState(attempts), now,
                    if (state.attempts.last().status == "PREPARED") "PENDING" else "UNKNOWN"
                )
            }
            if (updated != row) dao.saveListing(updated)
        }
    }

    fun prepare(id: Long, destinations: Set<RepostDestination>, now: Long): String? = transaction { dao ->
        val row = dao.listing(id) ?: return@transaction null
        if (row.status != "ACTIVE") return@transaction null
        val attemptId = UUID.randomUUID().toString()
        var updated = row
        destinations.filter { status(row, it) == "PENDING" }.forEach { destination ->
            val state = publication(updated, destination)
            updated = applyPublication(
                updated, destination, state.copy(
                    attempts = state.attempts +
                            PublishAttempt(attemptId, createdAt = now, updatedAt = now)
                ), now
            )
        }
        if (updated == row) return@transaction null
        dao.saveListing(updated); attemptId
    }

    fun changeAttempt(
        id: Long, destination: RepostDestination, attemptId: String, now: Long,
        change: (PublishAttempt) -> PublishAttempt
    ) = transaction { dao ->
        val row = dao.listing(id) ?: return@transaction
        val state = publication(row, destination)
        val next =
            state.copy(attempts = state.attempts.map { if (it.attemptId == attemptId) change(it).copy(updatedAt = now) else it })
        dao.saveListing(applyPublication(row, destination, next, now))
    }

    fun updatePublish(publishId: String, now: Long, change: (PublishAttempt) -> PublishAttempt) = transaction { dao ->
        dao.listings().forEach { row ->
            val state = publication(row, RepostDestination.TIKTOK)
            if (state.attempts.any { it.publishId == publishId }) {
                val next =
                    state.copy(attempts = state.attempts.map { if (it.publishId == publishId) change(it).copy(updatedAt = now) else it })
                dao.saveListing(applyPublication(row, RepostDestination.TIKTOK, next, now))
            }
        }
    }

    fun publication(row: ListingEntity, destination: RepostDestination): PublicationState =
        json.decodeFromString(if (destination == RepostDestination.TIKTOK) row.tiktokState else row.threadsState)

    fun status(row: ListingEntity, destination: RepostDestination) =
        if (destination == RepostDestination.TIKTOK) row.tiktokStatus else row.threadsStatus

    private fun applyPublication(
        row: ListingEntity, destination: RepostDestination, state: PublicationState, now: Long,
        overrideStatus: String? = null
    ): ListingEntity {
        val last = state.attempts.lastOrNull()
        val published = state.attempts.any { it.status == "PUBLISHED" }
        val status = overrideStatus ?: last?.status ?: "PENDING"
        return if (destination == RepostDestination.TIKTOK) row.copy(
            tiktokState = json.encodeToString(state),
            tiktokStatus = status,
            tiktokRepostedAt = row.tiktokRepostedAt ?: now.takeIf { published }, updatedAt = now
        )
        else row.copy(
            threadsState = json.encodeToString(state),
            threadsStatus = status,
            threadsRepostedAt = row.threadsRepostedAt ?: now.takeIf { published },
            updatedAt = now
        )
    }
}
