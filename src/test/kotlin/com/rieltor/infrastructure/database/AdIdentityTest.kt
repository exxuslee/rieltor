package com.rieltor.infrastructure.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.rieltor.domain.model.SourceMessage
import com.rieltor.domain.model.adId
import com.rieltor.domain.model.senderFromRaw
import com.rieltor.domain.model.userIdFromRaw
import com.rieltor.domain.service.CatalogAdsParser
import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.model.IncomingStatus
import com.rieltor.infrastructure.database.repository.CatalogRepository
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class AdIdentityTest {
    @Test fun `persists numeric sender from raw message and keeps it through content edits`() {
        val raw = """
            Message {
              id = 56844353536
              senderId = MessageSenderUser {
                userId = 1961809113
              }
              chatId = -1002681732909
            }
        """.trimIndent()
        assertEquals(1961809113L, userIdFromRaw(raw))
        assertNull(userIdFromRaw("senderId = MessageSenderChat { chatId = -100 }"))
        RoomDatabaseStore(Files.createTempDirectory("ad-sender").resolve("test.db")).use { db ->
            val repo = CatalogRepository(db)
            val source = SourceMessage(-1002681732909, 56844353536, 20,
                "Ірпінь\nКвартира\nЦіна: 80 000 USD", raw, 1000)
            repo.receive(source, 1000, 0)
            assertEquals(1961809113L, repo.source(source.chatId, source.messageId)?.userId)
            repo.receive(source.copy(text = "Ірпінь\nКвартира\nЦіна: 79 000 USD", raw = "MessageText {}", sourceEditedAt = 2000), 2000, 0)
            val rows = repo.incoming()
            assertEquals(1961809113L, rows.single().userId)
            assertEquals("1961809113:IRPIN:APARTMENT 1:79000:null:null",
                CatalogAdsParser().parse(rows, "APARTMENT 1", 2000).adId)
        }
    }

    @Test fun `identity normalizes areas and distinguishes missing from zero`() {
        assertEquals("123:IRPIN:HOUSE:80000:40:2.5", adId("123", "IRPIN", "HOUSE", 80000, 40.0, 2.50))
        assertNotEquals(adId("123", "IRPIN", "LAND", 1000, null, 2.0), adId("123", "IRPIN", "LAND", 1000, 0.0, 2.0))
        assertEquals("123", senderFromRaw("Message { senderId = MessageSenderUser {\n userId = 123\n } }"))
        assertEquals("chat--100", senderFromRaw("senderId = MessageSenderChat { chatId = -100 }"))
        assertNull(senderFromRaw("forwardInfo = MessageSenderUser { userId = 123 }"))
    }

    @Test fun `deduplicates across chats and Drive folders only by adId`() {
        RoomDatabaseStore(Files.createTempDirectory("ad-identity").resolve("test.db")).use { db ->
            val repo = CatalogRepository(db)
            fun publish(id: Long, sender: String = "123", price: Int = 80000, chat: Long = -100, area: String = "40") {
                val source = SourceMessage(chat, id, 20,
                    "Квартира\nІрпінь\nПлоща: $area м²\nЦіна: $price USD\nhttps://drive.google.com/drive/folders/folder$id",
                    "raw", id * 1000, userId = sender.toLong())
                repo.receive(source, 10000, 0)
                val rows = repo.incoming(source.chatId, source.messageId)
                repo.stage(rows, IncomingStatus.ReadyForMedia, 10000)
                val token = assertNotNull(repo.claim(rows, 10000))
                assertTrue(repo.promote(rows, token, CatalogAdsParser().parse(rows, "APARTMENT 1", 10000), 10000))
            }
            publish(1)
            val old = repo.listings().single()
            repo.save(old.copy(tiktokStatus = "PUBLISHED", tiktokRepostedAt = 1234))
            publish(2, chat = -200, area = "40.0")
            assertEquals(old.id, repo.listings().single().id)
            assertEquals(1234L, repo.listings().single().tiktokRepostedAt)
            assertEquals("123:IRPIN:APARTMENT 1:80000:40:null", repo.listings().single().adId)
            publish(3, sender = "456")
            publish(4, price = 79000)
            publish(5, area = "41")
            assertEquals(4, repo.listings().size)
            // Editing the existing source changes its key without leaving a stale card.
            publish(5, price = 81000, area = "41")
            assertEquals(4, repo.listings().size)
            assertEquals(81000, repo.listings().single { it.messageId == 5L }.price)
        }
    }

    @Test fun `v19 migration computes keys merges duplicates and preserves inbox and publication history`() {
        val path = Files.createTempDirectory("ad-migration").resolve("test.db")
        val schema = Json.parseToJsonElement(Files.readString(Path.of("schemas/com.rieltor.infrastructure.database.local.RieltorDatabase/19.json")))
            .jsonObject.getValue("database").jsonObject.getValue("entities").jsonArray
        BundledSQLiteDriver().open(path.toString()).use { connection ->
            schema.forEach { entity ->
                val data = entity.jsonObject
                val name = data.getValue("tableName").jsonPrimitive.content
                connection.execSQL(data.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", name))
                data.getValue("indices").jsonArray.forEach {
                    connection.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", name))
                }
            }
            fun insert(table: String, values: Map<String, String>) {
                val fields = schema.first { it.jsonObject.getValue("tableName").jsonPrimitive.content == table }
                    .jsonObject.getValue("fields").jsonArray.map { it.jsonObject }
                val columns = fields.joinToString { it.getValue("columnName").jsonPrimitive.content }
                val literals = fields.joinToString { field ->
                    val column = field.getValue("columnName").jsonPrimitive.content
                    values[column] ?: if (field["notNull"]?.jsonPrimitive?.boolean != true) "NULL"
                    else if (field.getValue("affinity").jsonPrimitive.content == "TEXT") "''" else "0"
                }
                connection.execSQL("INSERT INTO $table ($columns) VALUES ($literals)")
            }
            for (id in 1..3) {
                val sender = if (id == 3) 456 else 123
                val common = mapOf("id" to "$id", "chatId" to "-100", "messageId" to "$id", "groupKey" to "'-100:$id'", "sourceCreatedAt" to "${id * 1000}")
                insert("incoming_telegram_messages", common + mapOf("rawMessage" to "'senderId = MessageSenderUser { userId = $sender }'", "status" to "'PROMOTED'"))
                insert("listings", common + mapOf("location" to "'IRPIN'", "typeOfRealty" to "'HOUSE'", "price" to "80000", "areaM2" to "40.0",
                    "tiktokRepostedAt" to if (id == 1) "1234" else "NULL",
                    "tiktokStatus" to if (id == 1) "'PUBLISHED'" else "'PENDING'", "threadsStatus" to "'PENDING'",
                    "tiktokState" to "'{\"attempts\":[]}'", "threadsState" to "'{\"attempts\":[]}'"))
            }
            connection.execSQL("PRAGMA user_version=19")
        }
        RoomDatabaseStore(path).use { db ->
            val repo = CatalogRepository(db)
            assertEquals(3, repo.incoming().size)
            assertEquals(2, repo.listings().size)
            val merged = repo.listings().single { it.adId.startsWith("123:") }
            assertEquals(2, merged.id)
            assertEquals("123:IRPIN:HOUSE:80000:40:null", merged.adId)
            assertEquals(1234, merged.tiktokRepostedAt)
            assertEquals("PUBLISHED", merged.tiktokStatus)
            assertEquals(123L, repo.source(-100, 1)?.userId)
        }
    }
}
