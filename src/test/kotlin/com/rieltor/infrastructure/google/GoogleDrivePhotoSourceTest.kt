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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoogleDrivePhotoSourceTest {
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
            "https://drive.google.com/open?id=legacy-folder-id&usp=drive_copy",
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
            "https://drive.google.com/drive/folders/folder-123456",
            limit = 35,
        )

        assertEquals(listOf("one.jpg", "two.webp"), photos.map { it.fileName })
        assertContentEquals(byteArrayOf(1, 2, 3), photos[0].content.readBytes())
        assertContentEquals(byteArrayOf(4, 5), photos[1].content.readBytes())
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
            "https://drive.google.com/drive/folders/folder-with-broken-photo",
            limit = 1,
        )

        assertEquals(3, failedFileAttempts)
        assertEquals(listOf("good.jpg"), photos.map { it.fileName })
        assertContentEquals(byteArrayOf(7, 8), photos.single().content.readBytes())
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
                "https://drive.google.com/drive/folders/folder-with-only-broken-photo",
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
        val settings = ApplicationSettings(
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
        return GoogleDrivePhotoSource(client, auth, json)
    }
}
