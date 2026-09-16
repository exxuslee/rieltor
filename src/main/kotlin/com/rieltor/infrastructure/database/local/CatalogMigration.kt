package com.rieltor.infrastructure.database.local

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.rieltor.domain.model.PublicationState
import com.rieltor.domain.model.PublishAttempt
import com.rieltor.domain.model.sha256
import com.rieltor.infrastructure.config.JsonSettingsStore
import com.rieltor.infrastructure.config.SlotReservation
import com.rieltor.infrastructure.database.model.ListingEntity
import kotlinx.serialization.json.*
import java.nio.file.Path

/** JSON is flushed before the SQLite transaction can remove the legacy throttle tables. */
internal class CatalogMigration(private val settings: JsonSettingsStore, private val path: Path) : Migration(11, 12) {
    override fun migrate(connection: SQLiteConnection) {
        val json = Json { encodeDefaults = true }
        fun rows(table: String): List<JsonObject> = connection.prepare("SELECT * FROM $table").use { statement ->
            buildList {
                while (statement.step()) add(buildJsonObject {
                    repeat(statement.getColumnCount()) { index ->
                        put(statement.getColumnName(index), if (statement.isNull(index)) JsonNull else JsonPrimitive(statement.getText(index)))
                    }
                })
            }
        }
        val received = rows("received_telegram_messages")
        val queue = rows("telegram_repost_queue")
        val publications = rows("repost_publications")
        val published = rows("published_reposts")
        val tracked = rows("tiktok_tracked_publishes")
        val attempts = rows("tiktok_publish_attempts")
        val throttle = rows("tiktok_publish_throttle")
        val migrationKey = sha256(path.toAbsolutePath().normalize().toString())
        if (migrationKey !in settings.snapshot().migratedDatabases) settings.update { current ->
            current.copy(
                slotReservations = current.slotReservations + attempts.map {
                    SlotReservation("legacy:$migrationKey:${it.text("id")}", 0, it.number("attempted_at"))
                },
                blockedUntil = maxOf(current.blockedUntil, throttle.maxOfOrNull { it.number("blocked_until") } ?: 0),
                migratedDatabases = current.migratedDatabases + migrationKey,
            )
        }
        createCatalogTablesV12(connection)
        fun insert(table: String, values: JsonObject) {
            val fields = values.filterKeys { it != "id" }
            connection.prepare("INSERT INTO $table (${fields.keys.joinToString()}) VALUES (${fields.keys.joinToString { "?" }})").use { sql ->
                fields.values.forEachIndexed { index, value ->
                    val primitive = value as JsonPrimitive
                    when {
                        primitive is JsonNull -> sql.bindNull(index + 1)
                        primitive.isString -> sql.bindText(index + 1, primitive.content)
                        primitive.booleanOrNull != null -> sql.bindLong(index + 1, if (primitive.boolean) 1 else 0)
                        primitive.longOrNull != null -> sql.bindLong(index + 1, primitive.long)
                        else -> sql.bindDouble(index + 1, primitive.double)
                    }
                }
                sql.step()
            }
        }
        fun legacyListing(row: ListingEntity, legacyUpdateId: Long?, legacySnapshot: String): JsonObject =
            JsonObject(json.encodeToJsonElement(row).jsonObject + mapOf(
                "parserVersion" to JsonPrimitive(1),
                "parseWarnings" to JsonPrimitive("[]"),
                "legacyUpdateId" to (legacyUpdateId?.let(::JsonPrimitive) ?: JsonNull),
                "legacySnapshot" to JsonPrimitive(legacySnapshot),
            ))
        val ids = (received + queue + publications + published).map { it.number("telegram_update_id") }.distinct()
        val assignedTracked = mutableSetOf<String>()
        ids.forEach { oldId ->
            val originals = received.filter { it.number("telegram_update_id") == oldId }
            val queued = queue.filter { it.number("telegram_update_id") == oldId }
            val posts = publications.filter { it.number("telegram_update_id") == oldId }
            val historical = published.filter { it.number("telegram_update_id") == oldId }
            val source = (originals + queued + posts + historical).first()
            val snapshot = buildJsonObject {
                put("received", JsonArray(originals)); put("queue", JsonArray(queued))
                put("publications", JsonArray(posts)); put("published", JsonArray(historical))
            }.toString()
            val created = source.number("received_at").takeIf { it > 0 }?.times(1000) ?: source.number("created_at") * 1000
            val group = "legacy:$oldId"
            insert("incoming_telegram_messages", buildJsonObject {
                put("chatId", source.number("chat_id")); put("messageThreadId", source.number("message_thread_id"))
                put("mediaAlbumId", 0); put("groupKey", group); put("originalRawMessage", snapshot)
                put("rawMessage", snapshot); put("rawText", source.text("caption").orEmpty())
                put("sourceCreatedAt", created); put("sourceEditedAt", 0); put("receivedAt", created); put("updatedAt", created)
                put("contentHash", sha256(snapshot)); put("revision", 1); put("stableSince", created); put("verifyAfter", created)
                put("status", "NEEDS_REVIEW"); put("googleDriveUrls", source.text("google_drive_links") ?: "[]")
                put("mediaManifest", "[]"); put("attemptCount", 0); put("nextAttemptAt", 0)
                put("lastError", "Legacy messageId is unknown; source reconciliation required"); put("leaseUntil", 0)
                put("legacyUpdateId", oldId)
            })
            fun state(destination: String): PublicationState {
                val matching = posts.filter { it.text("destination") == destination }
                val legacyPosts = if (matching.isEmpty() && destination == "TIKTOK") historical else emptyList()
                return PublicationState((matching + legacyPosts).map { post ->
                    val publishId = post.text("publish_id")
                    val pending = tracked.firstOrNull { it.text("publish_id") == publishId }
                    if (pending != null && publishId != null) assignedTracked += publishId
                    val status = when {
                        pending?.text("last_status") == "PUBLISH_COMPLETE" -> "PUBLISHED"
                        pending != null && pending.text("mode") == "DRAFT" -> "DELIVERED_DRAFT"
                        pending != null -> "AWAITING_CONFIRMATION"
                        post.text("status") == "PUBLISHED" || post in legacyPosts -> "PUBLISHED"
                        post.text("status") == "FAILED" -> "FAILED"
                        post.text("status") == "DUPLICATE" -> "SKIPPED_DUPLICATE"
                        else -> "UNKNOWN"
                    }
                    PublishAttempt("legacy:$oldId:$destination", status, publishId, pending?.text("mode") ?: "POST",
                        pending?.number("created_at") ?: created, created, post.text("error"))
                })
            }
            val tik = state("TIKTOK"); val threads = state("THREADS")
            insert("listings", legacyListing(ListingEntity(
                groupKey = group, chatId = source.number("chat_id"), messageId = null,
                messageThreadId = source.number("message_thread_id"), rawMessage = snapshot, sourceRevision = "legacy",
                sourceCreatedAt = created, receivedAt = created, cdt = created, updatedAt = created,
                tiktokState = json.encodeToString(tik), threadsState = json.encodeToString(threads),
                tiktokStatus = tik.attempts.lastOrNull()?.status ?: "UNKNOWN", threadsStatus = threads.attempts.lastOrNull()?.status ?: "UNKNOWN",
                tiktokPublishId = tik.attempts.lastOrNull()?.publishId, threadsPublishId = threads.attempts.lastOrNull()?.publishId,
                tiktokReposted = tik.attempts.any { it.status == "PUBLISHED" }, threadsReposted = threads.attempts.any { it.status == "PUBLISHED" },
            ), oldId, snapshot))
        }
        // Preserve pending jobs even when the old cleanup already removed their source history.
        tracked.filter { it.text("publish_id") !in assignedTracked }.forEach { old ->
            val id = requireNotNull(old.text("publish_id")); val created = old.number("created_at")
            val state = PublicationState(listOf(PublishAttempt("legacy-pending:$id", "AWAITING_CONFIRMATION", id,
                old.text("mode") ?: "POST", created, old.number("updated_at"))))
            insert("listings", legacyListing(ListingEntity(
                groupKey = "legacy-pending:$id", chatId = 0, messageId = null, messageThreadId = 0,
                rawMessage = old.toString(), sourceRevision = "legacy", sourceCreatedAt = created,
                receivedAt = created, cdt = created, updatedAt = created,
                tiktokState = json.encodeToString(state), tiktokStatus = "AWAITING_CONFIRMATION", tiktokPublishId = id,
                threadsStatus = "UNKNOWN",
            ), null, old.toString()))
        }
        connection.prepare("SELECT COUNT(*) FROM incoming_telegram_messages").use { check(it.step() && it.getLong(0) == ids.size.toLong()) }
        listOf("received_telegram_messages", "telegram_repost_queue", "published_reposts", "repost_publications",
            "tiktok_publish_attempts", "tiktok_publish_throttle", "tiktok_tracked_publishes").forEach {
            connection.execSQL("DROP TABLE $it")
        }
    }
}

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
private fun JsonObject.number(key: String): Long = text(key)?.toLongOrNull() ?: 0
