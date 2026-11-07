package com.rieltor.infrastructure.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.rieltor.domain.model.*
import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.model.AdEntity
import com.rieltor.infrastructure.database.repository.CatalogRepository
import java.nio.file.Files
import kotlin.test.*

class RepostHistoryMigrationTest {
    @Test fun migrationPreservesStatusesDropsHistoryAndNeverRequeuesFailedSends() {
        val path = Files.createTempDirectory("repost-status-migration").resolve("test.db")
        RoomDatabaseStore(path).use { db ->
            val repo = CatalogRepository(db)
            for (n in 1L..6L) repo.save(AdEntity(
                id = n, chatId = REPOST_SOURCE_CHAT_ID, messageId = n, messageThreadId = 0,
                adId = "ad$n", sourceRevision = "1", timestamp = n,
                status = ListingStatus.Active, price = 10000, currency = "USD",
            ))
        }
        BundledSQLiteDriver().open(path.toString()).use { connection ->
            restoreLegacyRepostTables(connection)
            val statuses = listOf("PUBLISHED", "DELIVERED_DRAFT", "UNKNOWN", "FAILED", "SENDING")
            statuses.forEachIndexed { i, status ->
                val id = i + 1
                connection.execSQL("""INSERT INTO repostAttemptsTab VALUES ($id, NULL, NULL, '$status', 'PENDING', '{"attempts":[]}', '{"attempts":[]}')""")
            }
            connection.execSQL("""INSERT INTO repostTab VALUES (4, 100, NULL, 'PUBLISHED', 'PENDING', '{"attempts":[]}', '{"attempts":[]}')""")
            connection.execSQL("PRAGMA user_version=35")
        }
        repeat(2) {
            RoomDatabaseStore(path).use { db ->
                val repo = CatalogRepository(db)
                assertEquals(listOf("PUBLISHED", "DELIVERED_DRAFT", "UNKNOWN", "FAILED", "SENDING", "PENDING"),
                    repo.listings().sortedBy { it.id }.map { it.repostTikTok })
                assertEquals(6L, repo.nextRepost(true, false)?.id)
                assertTrue(repo.listings().all { it.repostThreads == "PENDING" })
            }
        }
        BundledSQLiteDriver().open(path.toString()).use { connection ->
            connection.prepare("SELECT name FROM sqlite_master WHERE name IN ('repostTab', 'repostAttemptsTab')").use {
                assertFalse(it.step())
            }
            connection.prepare("PRAGMA table_info(adsTab)").use { query ->
                val fields = buildSet { while(query.step()) add(query.getText(1)) }
                assertEquals(setOf("repostTikTok", "repostThreads"), fields.filter { it.startsWith("repost") }.toSet())
                assertTrue(fields.none { it.contains("publishId", true) || it.endsWith("State") || it.endsWith("RepostedAt") })
            }
            connection.prepare("PRAGMA foreign_key_check").use { assertFalse(it.step()) }
        }
    }
}
