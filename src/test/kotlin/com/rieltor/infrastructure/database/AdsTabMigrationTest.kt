package com.rieltor.infrastructure.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.rieltor.domain.model.CatalogFilter
import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.repository.CatalogListingQueryFactory
import com.rieltor.infrastructure.database.repository.CatalogRepository
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class AdsTabMigrationTest {
    @Test fun `v25 migration preserves listing fields and catalog queries`() = checkMigration(25)
    @Test fun `v27 migration separates repost fields without data loss`() = checkMigration(27)

    @Test fun `v28 migration preserves source timestamp and reposts`() = checkMigration(28)

    @Test fun `new listing creates repost with generated id and survives updates`() {
        val path = Files.createTempDirectory("repost-insert").resolve("test.db")
        RoomDatabaseStore(path).use { db ->
            val repo = CatalogRepository(db)
            val row = com.rieltor.infrastructure.database.model.AdEntity(
                chatId = -100, messageId = 1, adId = "new-ad", messageThreadId = 0,
                sourceRevision = "revision", timestamp = 100,
            )
            val id = repo.save(row)
            assertTrue(id > 0)
            assertEquals(row.copy(id = id), repo.listing(id))
            db.blocking { it.catalogDao().saveRepost(requireNotNull(repo.repost(id)).copy(tiktokStatus = "PUBLISHED", tiktokRepostedAt = 200)) }
            repo.save(requireNotNull(repo.listing(id)).copy(title = "Edited"))
            assertEquals(200L, repo.repost(id)?.tiktokRepostedAt)
            assertEquals("PENDING", repo.repost(id)?.threadsStatus)
            assertFails {
                db.blocking { it.catalogDao().saveRepost(com.rieltor.infrastructure.database.model.RepostEntity(id + 999)) }
            }
            assertEquals(1, repo.listings().size)
        }
    }

    private fun checkMigration(version: Int) {
        val path = Files.createTempDirectory("ads-rename").resolve("test.db")
        val schema = Json.parseToJsonElement(Files.readString(Path.of("schemas/com.rieltor.infrastructure.database.local.RieltorDatabase/$version.json")))
            .jsonObject.getValue("database").jsonObject.getValue("entities").jsonArray
        fun snapshot(): List<String?> = BundledSQLiteDriver().open(path.toString()).use { connection ->
            val table = connection.prepare("SELECT name FROM sqlite_master WHERE name='adsTab'").use {
                if (it.step()) "adsTab" else "listings"
            }
            val selection = if (table == "adsTab" && connection.prepare("SELECT name FROM sqlite_master WHERE name='repostTab'").use { it.step() })
                "SELECT adsTab.*, repostTab.tiktokRepostedAt, repostTab.threadsRepostedAt, repostTab.tiktokStatus, repostTab.threadsStatus, repostTab.tiktokState, repostTab.threadsState FROM adsTab JOIN repostTab USING(id) WHERE id=42"
            else "SELECT * FROM $table WHERE id=42"
            connection.prepare(selection).use { query ->
                assertTrue(query.step())
                (0 until query.getColumnCount()).filter { query.getColumnName(it) !in setOf("createdAt", "updatedAt", "publishedAt") }
                    .map { if (query.isNull(it)) null else query.getText(it) }
            }
        }
        BundledSQLiteDriver().open(path.toString()).use { connection ->
            schema.forEach { entity ->
                val data = entity.jsonObject
                val name = data.getValue("tableName").jsonPrimitive.content
                connection.execSQL(data.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", name))
                data.getValue("indices").jsonArray.forEach {
                    connection.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", name))
                }
                if (name == "listings" || name == "adsTab" || name == "repostTab") {
                    val values = mapOf("id" to "42", "chatId" to "-100", "messageId" to "7", "adId" to "'test-ad'",
                        "sourceCreatedAt" to "1111", "createdAt" to "2222", "updatedAt" to "3333", "publishedAt" to "4444",
                        "status" to "'ACTIVE'", "price" to "80000", "currency" to "'USD'",
                        "governmentPrograms" to "'[\"TEST\"]'", "tiktokRepostedAt" to "1234",
                        "tiktokStatus" to "'PUBLISHED'", "threadsStatus" to "'PENDING'",
                        "threadsRepostedAt" to "5678", "tiktokState" to "'{\"attempts\":[]}'",
                        "threadsState" to "'{\"attempts\":[],\"marker\":\"preserve\"}'")
                    val fields = data.getValue("fields").jsonArray.map { it.jsonObject }
                    val columns = fields.joinToString { it.getValue("columnName").jsonPrimitive.content }
                    val literals = fields.joinToString { field ->
                        values[field.getValue("columnName").jsonPrimitive.content]
                            ?: if (field["notNull"]?.jsonPrimitive?.boolean != true) "NULL"
                            else if (field.getValue("affinity").jsonPrimitive.content == "TEXT") "''" else "0"
                    }
                    connection.execSQL("INSERT INTO $name ($columns) VALUES ($literals)")
                }
            }
            connection.execSQL("PRAGMA user_version=$version")
        }
        val before = snapshot()
        RoomDatabaseStore(path).use { db ->
            val repo = CatalogRepository(db)
            assertEquals(42L, repo.listings().single().id)
            assertEquals(1111L, repo.listings().single().timestamp)
            assertEquals(42L, repo.publicListing(42)?.id)
            db.blocking { room ->
                val dao = room.catalogDao()
                assertEquals(42L, dao.query(CatalogListingQueryFactory.create(CatalogFilter(programs = listOf("TEST")))).single().id)
                assertEquals(42L, dao.nextRepost(false, true)?.listing?.id)
                assertNull(dao.nextRepost(true, false))
            }
        }
        assertEquals(before, snapshot())
        BundledSQLiteDriver().open(path.toString()).use { connection ->
            connection.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name='listings'").use { assertFalse(it.step()) }
            connection.prepare("PRAGMA integrity_check").use { assertTrue(it.step()); assertEquals("ok", it.getText(0)) }
        }
        RoomDatabaseStore(path).use { db ->
            val repo = CatalogRepository(db)
            repo.save(repo.listings().single().copy(title = "Updated"))
            assertEquals("Updated", repo.listing(42)?.title)
            db.blocking { it.catalogDao().saveRepost(requireNotNull(repo.repost(42)).copy(threadsStatus = "FAILED", threadsState = "{\"attempts\":[]}")) }
            assertEquals("FAILED", repo.repost(42)?.threadsStatus)
            assertEquals(1234L, repo.repost(42)?.tiktokRepostedAt)
            assertNull(repo.nextRepost(false, true))
            db.blocking { it.catalogDao().deleteListing(42) }
            assertTrue(repo.listings().isEmpty())
        }
        BundledSQLiteDriver().open(path.toString()).use { connection ->
            connection.prepare("SELECT COUNT(*) FROM repostTab").use {
                assertTrue(it.step()); assertEquals(0L, it.getLong(0))
            }
            connection.prepare("PRAGMA table_info(adsTab)").use { query ->
                val columns = buildSet { while (query.step()) add(query.getText(1)) }
                assertTrue(columns.none { it.startsWith("tiktok") || it.startsWith("threads") })
                assertTrue("timestamp" in columns)
                assertTrue(columns.intersect(setOf("sourceCreatedAt", "createdAt", "updatedAt", "publishedAt")).isEmpty())
            }
            connection.prepare("PRAGMA foreign_key_check").use { assertFalse(it.step()) }
        }
    }
}
