package com.rieltor.infrastructure.google

import com.rieltor.domain.model.StoredGoogleDriveTokens
import com.rieltor.domain.repository.GoogleDriveTokenRepository
import com.rieltor.infrastructure.config.ApplicationSettings
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.test.*

class GoogleDrivePhotoSourceTest {
    @Test fun `catalog worker waits for stable source then downloads newest independent of repost flags`() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("catalog-pipeline")
        val image = java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val bytes = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "jpg", it) }.toByteArray()
        var downloads = 0
        val client = HttpClient(MockEngine { request ->
            when {
                request.url.parameters["alt"] == "media" -> { downloads++; respond(bytes, headers = headersOf(HttpHeaders.ContentType, "image/jpeg")) }
                request.url.encodedPath.endsWith("/files") -> respond("""{"files":[{"id":"photo","name":"photo.jpg","mimeType":"image/jpeg","version":"1"}]}""")
                else -> respond("""{"id":"photo","name":"photo.jpg","mimeType":"image/jpeg","version":"1"}""")
            }
        })
        com.rieltor.infrastructure.database.local.RoomDatabaseStore(directory.resolve("test.db")).use { db ->
            db.settings.update { it.copy(topicTypeMapping = mapOf("-100:20" to "APARTMENT 1"), driveFileDelayMs = 0, tiktokEnabled = false, threadsEnabled = false) }
            val repo = com.rieltor.infrastructure.database.repository.CatalogRepository(db)
            var time = 0L
            var unavailable = false
            val messages = (1L..2L).associateWith { id -> com.rieltor.domain.model.SourceMessage(-100, id, 20,
                text = "Ірпінь\nКвартира\nЦіна: 80 000 USD\nhttps://drive.google.com/drive/folders/example$id", raw = "source-$id", sourceCreatedAt = id * 1000, userId = id) }.toMutableMap()
            val telegram = object : com.rieltor.application.port.TelegramInboxSource {
                override fun start() = Unit
                override fun close() = Unit
                override suspend fun refresh(chatId: Long, messageId: Long) = if (unavailable) com.rieltor.domain.model.SourceRefresh.Unavailable
                    else com.rieltor.domain.model.SourceRefresh.Found(messages.getValue(messageId))
            }
            val storage = com.rieltor.infrastructure.media.LocalPublicMediaStorage(directory.resolve("media"), "https://api.example")
            val service = com.rieltor.application.orchestration.CatalogIngestionService(telegram, repo, db.settings, createSource(client), storage) { time }
            messages.values.forEach { repo.receive(it, time, 1_200_000) }
            time = 1_199_999
            service.verifyDue(); assertFalse(service.downloadNext()); assertEquals(0, downloads)
            time = 1_200_000; unavailable = true
            service.verifyDue(); assertFalse(service.downloadNext()); assertEquals(0, downloads)
            time = 1_260_001; unavailable = false
            service.verifyDue(); assertTrue(service.downloadNext())
            assertEquals(2, repo.listings().single().messageId); assertEquals(1, downloads)
            assertTrue(service.downloadNext()); assertEquals(2, repo.listings().size)
            assertTrue(repo.listings().all { it.status == "ACTIVE" && it.tiktokRepostedAt == null })
            val original = repo.listings().first { it.messageId == 2L }
            val originalPhotos = Json.decodeFromString<List<com.rieltor.domain.model.CatalogPhoto>>(original.photos)
            time += 7 * 86_400_000
            messages[3] = messages.getValue(2).copy(messageId = 3, sourceCreatedAt = time,
                text = messages.getValue(2).text.replace("80 000", "75 000"))
            repo.receive(messages.getValue(3), time, 1_200_000)
            time += 1_200_000
            service.verifyDue(); assertTrue(service.downloadNext())
            val renewed = repo.listings().first { it.messageId == 3L }
            assertEquals(3, repo.listings().size)
            assertNotEquals(original.id, renewed.id)
            assertNotEquals(original.adId, renewed.adId)
            assertEquals(75_000L, renewed.price)
            val renewedPhotos = Json.decodeFromString<List<com.rieltor.domain.model.CatalogPhoto>>(renewed.photos)
            assertEquals(originalPhotos.single().checksum, renewedPhotos.single().checksum)
            assertEquals(3, downloads) // A different adId is a separate catalog entry.
            assertNotNull(repo.source(-100, 2))
            assertEquals(messages.getValue(3).sourceCreatedAt,
                java.nio.file.Files.getLastModifiedTime(storage.resolve(renewedPhotos.single().fileName)!!).toMillis())

            messages[4] = messages.getValue(3).copy(messageId = 4, text = "Квартира без ціни")
            repo.receive(messages.getValue(4), time, 0)
            service.verifyDue(); assertTrue(service.downloadNext())
            assertNull(repo.source(-100, 4))
            assertEquals(3, downloads)
            service.close()
        }
        client.close()
    }

    @Test fun `catalog rejects partial batch and changed Drive version`() = runBlocking {
        var version = "1"
        val client = HttpClient(MockEngine { request ->
            when {
                request.url.parameters["alt"] == "media" -> respond(byteArrayOf(1,2,3), headers = headersOf(HttpHeaders.ContentType, "image/jpeg"))
                request.url.encodedPath.endsWith("/files") -> respond("""{"files":[{"id":"photo","name":"photo.jpg","mimeType":"image/jpeg","version":"1"}]}""")
                else -> respond("""{"id":"photo","name":"photo.jpg","mimeType":"image/jpeg","version":"$version"}""")
            }
        })
        assertFailsWith<IllegalStateException> {
            createSource(client).downloadCatalogPhotos(listOf("https://drive.google.com/drive/folders/example"), 10, 0) { _, stream ->
                stream.readBytes(); version = "2"
            }
        }
        client.close()
    }
    @Test
    fun `extracts folder and file links from telegram text`() {
        val targets = GoogleDriveLinkParser.extract(
            """
            Фото: https://drive.google.com/drive/u/0/folders/folder_123-abc?usp=sharing
            Обкладинка: https://drive.google.com/file/d/file_456-def/view
            Старе посилання: https://drive.google.com/open?id=unknown_789-ghi&usp=drive_copy
            """.trimIndent()
        )

        assertEquals(
            listOf(
                DriveTarget.Folder("folder_123-abc"),
                DriveTarget.File("file_456-def"),
                DriveTarget.Unknown("unknown_789-ghi"),
            ),
            targets,
        )
    }

    @Test
    fun `resolves legacy open link as folder before downloading photos`() = runBlocking {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/drive/v3/files/legacy-folder-id" -> respond(
                    content = """{
                        "id":"legacy-folder-id",
                        "name":"Photos",
                        "mimeType":"application/vnd.google-apps.folder"
                    }""".trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                "/drive/v3/files" -> respond(
                    content = """{"files":[
                        {"id":"legacy-jpg-id","name":"house.jpg","mimeType":"image/jpeg","size":"3"}
                    ]}""".trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                "/drive/v3/files/legacy-jpg-id" -> respond(
                    byteArrayOf(1, 2, 3),
                    headers = headersOf(HttpHeaders.ContentType, "image/jpeg"),
                )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val source = createSource(HttpClient(engine))

        val photos = source.downloadPhotos(
            listOf("https://drive.google.com/open?id=legacy-folder-id&usp=drive_copy"),
            limit = 35,
        )

        assertEquals(listOf("house.jpg"), photos.map { it.fileName })
        assertContentEquals(byteArrayOf(1, 2, 3), photos.single().content.readBytes())
    }

    @Test
    fun `downloads only supported photos from folder`() = runBlocking {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath == "/drive/v3/files" -> respond(
                    content = """
                        {"files":[
                          {"id":"jpg-id","name":"one.jpg","mimeType":"image/jpeg","size":"3"},
                          {"id":"pdf-id","name":"note.pdf","mimeType":"application/pdf","size":"4"},
                          {"id":"webp-id","name":"two.webp","mimeType":"image/webp","size":"2"}
                        ]}
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                request.url.encodedPath.endsWith("/jpg-id") -> respond(
                    byteArrayOf(1, 2, 3),
                    headers = headersOf(HttpHeaders.ContentType, "image/jpeg"),
                )
                request.url.encodedPath.endsWith("/webp-id") -> respond(
                    byteArrayOf(4, 5),
                    headers = headersOf(HttpHeaders.ContentType, "image/webp"),
                )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val client = HttpClient(engine)
        val source = createSource(client)

        val photos = source.downloadPhotos(
            listOf("https://drive.google.com/drive/folders/folder-123456"),
            limit = 35,
        )

        assertEquals(listOf("one.jpg", "two.webp"), photos.map { it.fileName })
        assertContentEquals(byteArrayOf(1, 2, 3), photos[0].content.readBytes())
        assertContentEquals(byteArrayOf(4, 5), photos[1].content.readBytes())
    }

    @Test
    fun `prefers portrait photos before applying download limit`() = runBlocking {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath == "/drive/v3/files" -> respond(
                    content = """{"files":[
                        {"id":"landscape-id","name":"01-landscape.jpg","mimeType":"image/jpeg","imageMediaMetadata":{"width":1600,"height":900}},
                        {"id":"rotated-id","name":"02-rotated.jpg","mimeType":"image/jpeg","imageMediaMetadata":{"width":1600,"height":900,"rotation":1}},
                        {"id":"portrait-id","name":"03-portrait.jpg","mimeType":"image/jpeg","imageMediaMetadata":{"width":900,"height":1600}}
                    ]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                request.url.encodedPath.endsWith("/rotated-id") -> respond(byteArrayOf(2))
                request.url.encodedPath.endsWith("/portrait-id") -> respond(byteArrayOf(3))
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val source = createSource(HttpClient(engine))

        val photos = source.downloadPhotos(
            listOf("https://drive.google.com/drive/folders/folder-with-mixed-orientations"),
            limit = 2,
        )

        assertEquals(listOf("02-rotated.jpg", "03-portrait.jpg"), photos.map { it.fileName })
    }

    @Test
    fun `skips failed file after retries and continues with next photo`() = runBlocking {
        var failedFileAttempts = 0
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath == "/drive/v3/files" -> respond(
                    content = """{"files":[
                        {"id":"broken-id","name":"broken.jpg","mimeType":"image/jpeg","size":"3"},
                        {"id":"good-id","name":"good.jpg","mimeType":"image/jpeg","size":"2"}
                    ]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                request.url.encodedPath.endsWith("/broken-id") -> {
                    failedFileAttempts++
                    respond("temporary failure", HttpStatusCode.ServiceUnavailable)
                }
                request.url.encodedPath.endsWith("/good-id") -> respond(
                    byteArrayOf(7, 8),
                    headers = headersOf(HttpHeaders.ContentType, "image/jpeg"),
                )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val source = createSource(HttpClient(engine))

        val photos = source.downloadPhotos(
            listOf("https://drive.google.com/drive/folders/folder-with-broken-photo"),
            limit = 1,
        )

        assertEquals(2, failedFileAttempts)
        assertEquals(listOf("good.jpg"), photos.map { it.fileName })
        assertContentEquals(byteArrayOf(7, 8), photos.single().content.readBytes())
    }

    @Test
    fun `refreshes rejected access token and retries request once`() = runBlocking {
        val repository = InMemoryGoogleTokens(
            StoredGoogleDriveTokens("rejected-access", "persistent-refresh", Instant.now().epochSecond + 3600)
        )
        var rejectedDownloadAttempts = 0
        var refreshedDownloadAttempts = 0
        var tokenRefreshAttempts = 0
        val engine = MockEngine { request ->
            when {
                request.url.host == "oauth2.googleapis.com" -> {
                    tokenRefreshAttempts++
                    respond(
                        content = """{"access_token":"fresh-access","expires_in":3600}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                request.url.encodedPath == "/drive/v3/files" -> respond(
                    content = """{"files":[
                        {"id":"photo-id","name":"photo.jpg","mimeType":"image/jpeg","size":"3"}
                    ]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                request.url.encodedPath.endsWith("/photo-id") -> {
                    when (request.headers[HttpHeaders.Authorization]) {
                        "Bearer rejected-access" -> {
                            rejectedDownloadAttempts++
                            respond("unauthorized", HttpStatusCode.Unauthorized)
                        }
                        "Bearer fresh-access" -> {
                            refreshedDownloadAttempts++
                            respond(byteArrayOf(1, 2, 3), headers = headersOf(HttpHeaders.ContentType, "image/jpeg"))
                        }
                        else -> error("Unexpected authorization header")
                    }
                }
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val client = HttpClient(engine)
        val settings = googleSettings()
        val json = Json { ignoreUnknownKeys = true }
        val source = GoogleDrivePhotoSource(
            client,
            GoogleDriveAuthService(client, settings, repository, json, tokenRetryDelayMillis = 0),
            json,
        )

        val photos = source.downloadPhotos(
            listOf("https://drive.google.com/drive/folders/folder-with-expired-token"),
            limit = 1,
        )

        assertContentEquals(byteArrayOf(1, 2, 3), photos.single().content.readBytes())
        assertEquals(1, rejectedDownloadAttempts)
        assertEquals(1, tokenRefreshAttempts)
        assertEquals(1, refreshedDownloadAttempts)
        assertEquals("fresh-access", repository.load()?.accessToken)
    }

    @Test
    fun `reports failure when every candidate photo fails`() = runBlocking {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath == "/drive/v3/files" -> respond(
                    content = """{"files":[
                        {"id":"broken-id","name":"broken.jpg","mimeType":"image/jpeg","size":"3"}
                    ]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                request.url.encodedPath.endsWith("/broken-id") -> respond(
                    "temporary failure",
                    HttpStatusCode.ServiceUnavailable,
                )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val source = createSource(HttpClient(engine))

        val error = assertFailsWith<GoogleDriveAuthException> {
            source.downloadPhotos(
                listOf("https://drive.google.com/drive/folders/folder-with-only-broken-photo"),
                limit = 10,
            )
        }

        assertTrue(error.message.orEmpty().contains("could not download any"))
        assertTrue(error.message.orEmpty().contains("temporary failure"))
    }

    private fun createSource(client: HttpClient): GoogleDrivePhotoSource {
        val repository = object : GoogleDriveTokenRepository {
            override fun save(tokens: StoredGoogleDriveTokens) = Unit
            override fun load() = StoredGoogleDriveTokens(
                "access",
                "refresh",
                Instant.now().epochSecond + 3600,
            )
        }
        val settings = googleSettings()
        val json = Json { ignoreUnknownKeys = true }
        val auth = GoogleDriveAuthService(client, settings, repository, json)
        return GoogleDrivePhotoSource(client, auth, json)
    }

    private fun googleSettings() = ApplicationSettings(
        mediaDirectory = java.nio.file.Path.of("media"),
        publicBaseUrl = "https://api.example",
        telegramApiId = 1,
        telegramApiHash = "hash",
        telegramSessionDirectory = java.nio.file.Path.of("telegram"),
        tikTokClientKey = "key",
        tikTokClientSecret = "secret",
        tikTokRedirectUri = "https://api.example/auth/tiktok/callback",
        googleClientId = "google-client-id",
        googleClientSecret = "google-client-secret",
        googleRedirectUri = "https://api.example/auth/google/callback",
    )

    private class InMemoryGoogleTokens(private var value: StoredGoogleDriveTokens?) : GoogleDriveTokenRepository {
        override fun save(tokens: StoredGoogleDriveTokens) {
            value = tokens
        }

        override fun load(): StoredGoogleDriveTokens? = value
    }
}
