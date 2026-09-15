package com.rieltor.infrastructure.database.repository

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.rieltor.domain.model.*
import com.rieltor.domain.service.GoogleDriveLinkExtractor
import com.rieltor.infrastructure.database.local.CatalogDao
import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.model.IncomingEntity
import com.rieltor.infrastructure.database.model.ListingEntity
import kotlinx.serialization.json.Json
import java.util.*

class CatalogRepository(private val database: RoomDatabaseStore) {
    private val json = Json { encodeDefaults = true }
    @Synchronized private fun <T> transaction(block: suspend (CatalogDao) -> T): T = database.blocking { room ->
        room.useWriterConnection { it.immediateTransaction { block(room.catalogDao()) } }
    }
    fun receive(message: SourceMessage, now: Long, stabilityMs: Long) = transaction { dao ->
        val old = dao.source(message.chatId, message.messageId)
        if (old?.contentHash == message.fingerprint() || old?.status == "DELETED") return@transaction
        if (old != null && message.sourceEditedAt < old.sourceEditedAt) return@transaction
        val row = IncomingEntity(
            id = old?.id ?: 0, chatId = message.chatId, messageId = message.messageId,
            messageThreadId = message.messageThreadId, mediaAlbumId = message.mediaAlbumId, groupKey = message.groupKey,
            originalRawMessage = old?.originalRawMessage ?: message.raw, rawMessage = message.raw, rawText = message.text,
            sourceCreatedAt = message.sourceCreatedAt, sourceEditedAt = message.sourceEditedAt,
            receivedAt = old?.receivedAt ?: now, updatedAt = now, contentHash = message.fingerprint(),
            revision = (old?.revision ?: 0) + 1, stableSince = now, verifyAfter = now + stabilityMs,
            googleDriveUrls = json.encodeToString(GoogleDriveLinkExtractor().extract(message.text)),
            listingId = old?.listingId,
        )
        dao.saveSource(row)
        // A late album item invalidates every part, including a currently running media lease.
        dao.group(message.groupKey).filter { it.status != "DELETED" }.forEach {
            dao.saveSource(it.copy(status = "WAITING_STABILITY", stableSince = now, verifyAfter = now + stabilityMs,
                verifiedAt = null, leaseToken = null, leaseUntil = 0, attemptCount = 0, nextAttemptAt = 0))
        }
        dao.listingForGroup(message.groupKey)?.let { dao.saveListing(it.copy(status = "HIDDEN", updatedAt = now)) }
    }
    fun delete(chatId: Long, messageId: Long, now: Long) = transaction { dao ->
        val row = dao.source(chatId, messageId) ?: return@transaction
        dao.group(row.groupKey).forEach { dao.saveSource(it.copy(status = "DELETED", deletedAt = now, leaseToken = null, leaseUntil = 0)) }
        dao.listingForGroup(row.groupKey)?.let { dao.saveListing(it.copy(status = "HIDDEN", updatedAt = now)) }
    }
    fun source(chatId: Long, messageId: Long) = transaction { it.source(chatId, messageId) }
    fun group(key: String) = transaction { it.group(key) }
    fun groups() = transaction { it.sources() }.groupBy { it.groupKey }.values.map { it.sortedBy { row -> row.messageId } }
    fun listing(id: Long) = transaction { it.listing(id) }
    fun listings() = transaction { it.listings() }
    fun save(row: ListingEntity): Long = transaction { it.saveListing(row).takeIf { id -> id > 0 } ?: row.id }
    fun query(query: androidx.room.RoomRawQuery) = database.blocking { it.catalogDao().query(query) }
    fun revision(rows: List<IncomingEntity>) = sha256(rows.joinToString("|") { "${it.id}:${it.revision}:${it.contentHash}" })

    fun stage(rows: List<IncomingEntity>, status: String, now: Long, error: String? = null, next: Long = 0): Boolean = transaction { dao ->
        val current = dao.group(rows.first().groupKey)
        if (revision(current) != revision(rows) || current.any { it.status == "DELETED" }) return@transaction false
        current.forEach { dao.saveSource(it.copy(status = status, updatedAt = now,
            verifiedAt = if (status in setOf("READY_FOR_MEDIA", "PROMOTED")) now else it.verifiedAt,
            lastError = error, nextAttemptAt = next, leaseToken = null, leaseUntil = 0,
            attemptCount = if (error != null) it.attemptCount + 1 else it.attemptCount)) }
        true
    }
    fun claim(rows: List<IncomingEntity>, now: Long): String? = transaction { dao ->
        val current = dao.group(rows.first().groupKey)
        if (revision(current) != revision(rows) || current.any { it.status !in setOf("READY_FOR_MEDIA", "MEDIA_RETRY", "MEDIA_READY") || it.nextAttemptAt > now || it.leaseUntil > now }) return@transaction null
        val token = UUID.randomUUID().toString()
        current.forEach { dao.saveSource(it.copy(status = "DOWNLOADING", leaseToken = token, leaseUntil = now + 120_000)) }
        token
    }
    fun manifest(rows: List<IncomingEntity>, token: String, photos: List<CatalogPhoto>, now: Long): Boolean = transaction { dao ->
        val current = dao.group(rows.first().groupKey)
        if (revision(current) != revision(rows) || current.any { it.leaseToken != token }) return@transaction false
        current.forEach { dao.saveSource(it.copy(mediaManifest = json.encodeToString(photos), leaseUntil = now + 120_000)) }
        true
    }
    fun promote(rows: List<IncomingEntity>, token: String, prepared: ListingEntity, now: Long): Boolean = transaction { dao ->
        val current = dao.group(rows.first().groupKey)
        if (revision(current) != revision(rows) || current.any { it.leaseToken != token || it.status != "DOWNLOADING" }) return@transaction false
        val old = dao.listingForGroup(prepared.groupKey)
        val row = if (old == null) prepared else prepared.copy(id = old.id, cdt = old.cdt, publishedAt = old.publishedAt ?: now,
            tiktokReposted = old.tiktokReposted, threadsReposted = old.threadsReposted,
            tiktokRepostedAt = old.tiktokRepostedAt, threadsRepostedAt = old.threadsRepostedAt,
            tiktokStatus = old.tiktokStatus, threadsStatus = old.threadsStatus,
            tiktokPublishId = old.tiktokPublishId, threadsPublishId = old.threadsPublishId,
            tiktokState = old.tiktokState, threadsState = old.threadsState)
        val id = dao.saveListing(row).takeIf { it > 0 } ?: row.id
        current.forEach { dao.saveSource(it.copy(status = "PROMOTED", listingId = id, promotedAt = now, leaseToken = null, leaseUntil = 0)) }
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
                if (attempts != state.attempts) updated = applyPublication(updated, destination, PublicationState(attempts), now,
                    if (state.attempts.last().status == "PREPARED") "PENDING" else "UNKNOWN")
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
            updated = applyPublication(updated, destination, state.copy(attempts = state.attempts +
                PublishAttempt(attemptId, createdAt = now, updatedAt = now)), now)
        }
        if (updated == row) return@transaction null
        dao.saveListing(updated); attemptId
    }
    fun changeAttempt(id: Long, destination: RepostDestination, attemptId: String, now: Long,
        change: (PublishAttempt) -> PublishAttempt) = transaction { dao ->
        val row = dao.listing(id) ?: return@transaction
        val state = publication(row, destination)
        val next = state.copy(attempts = state.attempts.map { if (it.attemptId == attemptId) change(it).copy(updatedAt = now) else it })
        dao.saveListing(applyPublication(row, destination, next, now))
    }
    fun updatePublish(publishId: String, now: Long, change: (PublishAttempt) -> PublishAttempt) = transaction { dao ->
        dao.listings().forEach { row ->
            val state = publication(row, RepostDestination.TIKTOK)
            if (state.attempts.any { it.publishId == publishId }) {
                val next = state.copy(attempts = state.attempts.map { if (it.publishId == publishId) change(it).copy(updatedAt = now) else it })
                dao.saveListing(applyPublication(row, RepostDestination.TIKTOK, next, now))
            }
        }
    }
    fun publication(row: ListingEntity, destination: RepostDestination): PublicationState =
        json.decodeFromString(if (destination == RepostDestination.TIKTOK) row.tiktokState else row.threadsState)
    fun status(row: ListingEntity, destination: RepostDestination) = if (destination == RepostDestination.TIKTOK) row.tiktokStatus else row.threadsStatus
    private fun applyPublication(row: ListingEntity, destination: RepostDestination, state: PublicationState, now: Long,
        overrideStatus: String? = null): ListingEntity {
        val last = state.attempts.lastOrNull()
        val published = state.attempts.any { it.status == "PUBLISHED" }
        val status = overrideStatus ?: last?.status ?: "PENDING"
        return if (destination == RepostDestination.TIKTOK) row.copy(tiktokState = json.encodeToString(state),
            tiktokStatus = status, tiktokPublishId = last?.publishId, tiktokReposted = published,
            tiktokRepostedAt = row.tiktokRepostedAt ?: now.takeIf { published }, updatedAt = now)
        else row.copy(threadsState = json.encodeToString(state), threadsStatus = status, threadsPublishId = last?.publishId,
            threadsReposted = published, threadsRepostedAt = row.threadsRepostedAt ?: now.takeIf { published }, updatedAt = now)
    }
}
