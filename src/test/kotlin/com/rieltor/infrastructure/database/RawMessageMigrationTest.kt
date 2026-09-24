package com.rieltor.infrastructure.database
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.mapper.CatalogListingMapper
import com.rieltor.infrastructure.database.repository.CatalogRepository
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RawMessageMigrationTest {
    @Test fun `incoming user ID drives ad identity and survives edits`() {
        RoomDatabaseStore(Files.createTempDirectory("user-id-only").resolve("test.db")).use { db ->
            val repo = CatalogRepository(db)
            val message = com.rieltor.domain.model.SourceMessage(
                chatId = -100, messageId = 1, messageThreadId = 0,
                text = "original", raw = "", sourceCreatedAt = 1000, userId = 123L,
            )
            repo.receive(message, 1000, 0)
            repo.receive(message.copy(text = "edited", userId = null, sourceEditedAt = 2000), 2000, 0)
            val row = requireNotNull(repo.source(-100, 1))
            assertEquals(123L, row.userId)
            assertTrue(CatalogListingMapper().fromIncoming(row, "HOUSE", 2000).adId.startsWith("123:"))
        }
    }

    @Test fun `v23 migration removes raw dump and preserves retry state`() = checkMigration(23)
    @Test fun `v24 migration removes received timestamp and preserves inbox`() = checkMigration(24)

    @Test fun `v26 migration recovers numeric user ID`() = checkMigration(26, "123", null, 123L)
    @Test fun `v26 migration preserves existing user ID`() = checkMigration(26, "123", 456L, 456L)
    @Test fun `v26 migration does not treat channel ID as user ID`() = checkMigration(26)
    @Test fun `v26 migration ignores invalid identity`() = checkMigration(26, "invalid")

    private fun checkMigration(version: Int, sender: String = "chat--200", userId: Long? = null, expectedUserId: Long? = null) {
        val path = Files.createTempDirectory("raw-removal").resolve("test.db")
        val schema = Json.parseToJsonElement(Files.readString(Path.of("schemas/com.rieltor.infrastructure.database.local.RieltorDatabase/$version.json")))
            .jsonObject.getValue("database").jsonObject.getValue("entities").jsonArray
        BundledSQLiteDriver().open(path.toString()).use { connection ->
            schema.forEach { entity ->
                val data = entity.jsonObject
                val name = data.getValue("tableName").jsonPrimitive.content
                connection.execSQL(data.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", name))
                data.getValue("indices").jsonArray.forEach {
                    connection.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", name))
                }
                if (name == "incomeTab") {
                    val values = mapOf("id" to "1", "chatId" to "-100", "messageId" to "1",
                        "rawMessage" to "'senderId = MessageSenderChat { chatId = -200 }'",
                        "senderIdentity" to "'$sender'", "userId" to (userId?.toString() ?: "NULL"), "receivedAt" to "1234", "sourceCreatedAt" to "1000",
                        "status" to "'MEDIA_RETRY'", "attemptCount" to "3", "nextAttemptAt" to "9000")
                    val fields = data.getValue("fields").jsonArray.map { it.jsonObject }
                    val columns = fields.joinToString { it.getValue("columnName").jsonPrimitive.content }
                    val literals = fields.joinToString { field ->
                        values[field.getValue("columnName").jsonPrimitive.content]
                            ?: if (field["notNull"]?.jsonPrimitive?.boolean != true) "NULL"
                            else if (field.getValue("affinity").jsonPrimitive.content == "TEXT") "''" else "0"
                    }
                    connection.execSQL("INSERT INTO incomeTab ($columns) VALUES ($literals)")
                }
            }
            connection.execSQL("PRAGMA user_version=$version")
        }
        RoomDatabaseStore(path).use { db ->
            val row = CatalogRepository(db).incoming().single()
            assertEquals(expectedUserId, row.userId)
            assertEquals(1000L, row.sourceCreatedAt)
            assertEquals(3, row.attemptCount)
            assertEquals(9000L, row.nextAttemptAt)
            assertTrue(CatalogListingMapper().fromIncoming(row, "HOUSE", 10000).adId.startsWith("${expectedUserId ?: "unknown--100:1"}:"))
        }
        BundledSQLiteDriver().open(path.toString()).use { connection ->
            connection.prepare("PRAGMA table_info(incomeTab)").use { query ->
                val columns = buildSet { while (query.step()) add(query.getText(1)) }
                assertFalse("rawMessage" in columns)
                assertFalse("receivedAt" in columns)
                assertFalse("senderIdentity" in columns)
                assertTrue("rawText" in columns)
            }
        }
    }

}
