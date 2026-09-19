package com.rieltor.infrastructure.database.local

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.rieltor.domain.model.PublicationState
import com.rieltor.domain.model.adId
import com.rieltor.domain.model.senderFromRaw
import com.rieltor.domain.model.userIdFromRaw
import kotlinx.serialization.json.Json

/** Preserve the inbox, catalog IDs and publication attempts when introducing ad identity. */
internal fun migrateAdIds(connection: SQLiteConnection) {
    connection.execSQL("ALTER TABLE incoming_telegram_messages RENAME COLUMN groupKey TO sourceKey")
    connection.execSQL("ALTER TABLE incoming_telegram_messages ADD COLUMN userId INTEGER")
    connection.execSQL("DROP INDEX index_incoming_telegram_messages_groupKey")
    connection.execSQL("CREATE INDEX index_incoming_telegram_messages_sourceKey ON incoming_telegram_messages(sourceKey)")
    connection.execSQL("ALTER TABLE listings RENAME COLUMN groupKey TO sourceKey")
    connection.execSQL("DROP INDEX index_listings_groupKey")
    connection.execSQL("CREATE UNIQUE INDEX index_listings_sourceKey ON listings(sourceKey)")
    connection.execSQL("ALTER TABLE listings ADD COLUMN adId TEXT NOT NULL DEFAULT ''")
    val senders = mutableMapOf<String, String>()
    connection.prepare("SELECT id, sourceKey, rawMessage FROM incoming_telegram_messages").use { query ->
        while (query.step()) {
            val sender = senderFromRaw(query.getText(2)) ?: continue
            senders[query.getText(1)] = sender
            val userId = userIdFromRaw(query.getText(2)) ?: continue
            connection.prepare("UPDATE incoming_telegram_messages SET userId=? WHERE id=?").use {
                it.bindLong(1, userId); it.bindLong(2, query.getLong(0)); it.step()
            }
        }
    }
    val duplicates = mutableMapOf<String, MutableList<Long>>()
    connection.prepare("SELECT id, sourceKey, location, typeOfRealty, price, areaM2, landAreaSotka FROM listings ORDER BY sourceCreatedAt DESC, id DESC").use { query ->
        while (query.step()) {
            fun text(index: Int) = if (query.isNull(index)) null else query.getText(index)
            fun decimal(index: Int) = if (query.isNull(index)) null else query.getDouble(index)
            val source = query.getText(1)
            val key = adId(senders[source] ?: "unknown-$source", text(2), text(3),
                if (query.isNull(4)) null else query.getLong(4), decimal(5), decimal(6))
            val id = query.getLong(0)
            duplicates.getOrPut(key) { mutableListOf() }.add(id)
            connection.prepare("UPDATE listings SET adId=? WHERE id=?").use {
                it.bindText(1, key); it.bindLong(2, id); it.step()
            }
        }
    }
    duplicates.values.filter { it.size > 1 }.forEach { ids ->
        val survivor = ids.first()
        // Keep latest content, retaining successful publication dates and every attempt.
        for (destination in listOf("tiktok", "threads")) {
            val attempts = mutableListOf<com.rieltor.domain.model.PublishAttempt>()
            var publishedAt: Long? = null
            var status: String? = null
            ids.forEach { id ->
                connection.prepare("SELECT ${destination}State, ${destination}RepostedAt, ${destination}Status FROM listings WHERE id=?").use {
                    it.bindLong(1, id); check(it.step())
                    attempts += Json.decodeFromString<PublicationState>(it.getText(0)).attempts
                    if (!it.isNull(1)) publishedAt = minOf(publishedAt ?: Long.MAX_VALUE, it.getLong(1))
                    if (status == null || it.getText(2) == "PUBLISHED") status = it.getText(2)
                }
            }
            connection.prepare("UPDATE listings SET ${destination}State=?, ${destination}RepostedAt=?, ${destination}Status=? WHERE id=?").use {
                it.bindText(1, Json.encodeToString(PublicationState(attempts.distinctBy { a -> a.attemptId }.sortedBy { a -> a.createdAt })))
                publishedAt?.let { date -> it.bindLong(2, date) } ?: it.bindNull(2)
                it.bindText(3, requireNotNull(status)); it.bindLong(4, survivor); it.step()
            }
        }
        ids.drop(1).forEach { id -> connection.execSQL("DELETE FROM listings WHERE id=$id") }
    }
    connection.execSQL("CREATE UNIQUE INDEX index_listings_adId ON listings(adId)")
}
