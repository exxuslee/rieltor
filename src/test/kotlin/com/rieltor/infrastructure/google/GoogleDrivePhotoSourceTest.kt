package com.rieltor.infrastructure.google

import com.rieltor.domain.model.StoredGoogleDriveTokens
import com.rieltor.domain.repository.GoogleDriveTokenRepository
import com.rieltor.infrastructure.config.ApplicationSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class GoogleDrivePhotoSourceTest {
    @Test
    fun `extracts folder and file links from telegram text`() {
        val targets = GoogleDriveLinkParser.extract(
            """
            Фото: https://drive.google.com/drive/u/0/folders/folder_123-abc?usp=sharing
            Обкладинка: https://drive.google.com/file/d/file_456-def/view
            """.trimIndent()
        )

        assertEquals(
            listOf(DriveTarget.Folder("folder_123-abc"), DriveTarget.File("file_456-def")),
            targets,
        )
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
        val repository = object : GoogleDriveTokenRepository {
            override fun save(tokens: StoredGoogleDriveTokens) = Unit
            override fun load() = StoredGoogleDriveTokens(
                "access",
                "refresh",
                Instant.now().epochSecond + 3600,
            )
        }
        val settings = ApplicationSettings(
            port = 8383,
            databasePath = java.nio.file.Path.of("unused.db"),
            mediaDirectory = java.nio.file.Path.of("media"),
            publicBaseUrl = "https://api.example",
            telegramApiId = 1,
            telegramApiHash = "hash",
            telegramSessionDirectory = java.nio.file.Path.of("telegram"),
            tikTokClientKey = "key",
            tikTokClientSecret = "secret",
            tikTokRedirectUri = "https://api.example/auth/tiktok/callback",
        )
        val json = Json { ignoreUnknownKeys = true }
        val auth = GoogleDriveAuthService(client, settings, repository, json)
        val source = GoogleDrivePhotoSource(client, auth, json)

        val photos = source.downloadPhotos(
            "https://drive.google.com/drive/folders/folder-123456",
            limit = 35,
        )

        assertEquals(listOf("one.jpg", "two.webp"), photos.map { it.fileName })
        assertContentEquals(byteArrayOf(1, 2, 3), photos[0].content.readBytes())
        assertContentEquals(byteArrayOf(4, 5), photos[1].content.readBytes())
    }
}
