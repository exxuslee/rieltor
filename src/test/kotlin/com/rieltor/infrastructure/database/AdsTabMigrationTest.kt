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
    @Test fun `rename preserves all listing fields and catalog queries work`() {
        val path = Files.createTempDirectory("ads-rename").resolve("test.db")
        val schema = Json.parseToJsonElement(Files.readString(Path.of("schemas/com.rieltor.infrastructure.database.local.RieltorDatabase/25.json")))
            .jsonObject.getValue("database").jsonObject.getValue("entities").jsonArray
        fun snapshot(): List<String?> = BundledSQLiteDriver().open(path.toString()).use { connection ->
            val table = connection.prepare("SELECT name FROM sqlite_master WHERE name='adsTab'").use {
                if (it.step()) "adsTab" else "listings"
            }
            connection.prepare("SELECT * FROM $table WHERE id=42").use { query ->
                assertTrue(query.step())
                List(query.getColumnCount()) { if (query.isNull(it)) null else query.getText(it) }
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
                if (name == "listings") {
                    val values = mapOf("id" to "42", "chatId" to "-100", "messageId" to "7", "adId" to "'test-ad'",
                        "status" to "'ACTIVE'", "price" to "80000", "currency" to "'USD'",
                        "governmentPrograms" to "'[\"TEST\"]'", "tiktokRepostedAt" to "1234",
                        "tiktokStatus" to "'PUBLISHED'", "threadsStatus" to "'PENDING'")
                    val fields = data.getValue("fields").jsonArray.map { it.jsonObject }
                    val columns = fields.joinToString { it.getValue("columnName").jsonPrimitive.content }
                    val literals = fields.joinToString { field ->
                        values[field.getValue("columnName").jsonPrimitive.content]
                            ?: if (field["notNull"]?.jsonPrimitive?.boolean != true) "NULL"
                            else if (field.getValue("affinity").jsonPrimitive.content == "TEXT") "''" else "0"
                    }
                    connection.execSQL("INSERT INTO listings ($columns) VALUES ($literals)")
                }
            }
            connection.execSQL("PRAGMA user_version=25")
        }
        val before = snapshot()
        RoomDatabaseStore(path).use { db ->
            val repo = CatalogRepository(db)
            assertEquals(42L, repo.listings().single().id)
            assertEquals(42L, repo.publicListing(42)?.id)
            db.blocking { room ->
                val dao = room.catalogDao()
                assertEquals(42L, dao.query(CatalogListingQueryFactory.create(CatalogFilter(programs = listOf("TEST")))).single().id)
                assertEquals(42L, dao.nextRepost(false, true)?.id)
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
            db.blocking { it.catalogDao().deleteListing(42) }
            assertTrue(repo.listings().isEmpty())
        }
    }
}
