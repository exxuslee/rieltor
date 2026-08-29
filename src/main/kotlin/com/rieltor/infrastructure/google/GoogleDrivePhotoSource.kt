package com.rieltor.infrastructure.google

import com.rieltor.domain.model.TelegramPhoto
import com.rieltor.domain.repository.ExternalPhotoSource
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream

sealed interface DriveTarget {
    val id: String

    data class File(override val id: String) : DriveTarget
    data class Folder(override val id: String) : DriveTarget
}

object GoogleDriveLinkParser {
    private const val ID = "[A-Za-z0-9_-]+"
    private val folderPattern = Regex(
        """https?://(?:www\.)?drive\.google\.com/drive/(?:u/\d+/)?folders/($ID)""",
        RegexOption.IGNORE_CASE,
    )
    private val filePattern = Regex(
        """https?://(?:www\.)?drive\.google\.com/file/d/($ID)""",
        RegexOption.IGNORE_CASE,
    )
    private val queryFilePattern = Regex(
        """https?://(?:www\.)?drive\.google\.com/(?:open|uc)\?(?:[^\s#&]+&)*id=($ID)""",
        RegexOption.IGNORE_CASE,
    )

    fun extract(text: String?): List<DriveTarget> {
        if (text.isNullOrBlank()) return emptyList()
        val matches = buildList {
            folderPattern.findAll(text).forEach { add(it.range.first to DriveTarget.Folder(it.groupValues[1])) }
            filePattern.findAll(text).forEach { add(it.range.first to DriveTarget.File(it.groupValues[1])) }
            queryFilePattern.findAll(text).forEach { add(it.range.first to DriveTarget.File(it.groupValues[1])) }
        }
        return matches.sortedBy { it.first }.map { it.second }.distinct()
    }
}

class GoogleDrivePhotoSource(
    private val httpClient: HttpClient,
    private val auth: GoogleDriveAuthService,
    private val json: Json,
) : ExternalPhotoSource {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun containsLink(text: String?): Boolean = GoogleDriveLinkParser.extract(text).isNotEmpty()

    override suspend fun downloadPhotos(text: String?, limit: Int): List<TelegramPhoto> {
        if (limit <= 0) return emptyList()
        val targets = GoogleDriveLinkParser.extract(text)
        if (targets.isEmpty()) return emptyList()

        val token = auth.validAccessToken()
        val metadata = linkedMapOf<String, DriveFileMetadata>()
        targets.forEach { target ->
            val targetFiles = when (target) {
                is DriveTarget.File -> listOf(getFileMetadata(target.id, token))
                is DriveTarget.Folder -> listFolderFiles(target.id, token)
            }
            targetFiles.forEach { file -> metadata.putIfAbsent(file.id, file) }
        }

        val result = mutableListOf<TelegramPhoto>()
        try {
            metadata.values.asSequence()
                .filter { it.mimeType in SUPPORTED_IMAGE_MIME_TYPES }
                .filter { it.capabilities?.canDownload != false }
                .take(limit)
                .forEach { file ->
                    val bytes = downloadFile(file, token)
                    result += TelegramPhoto(safeFileName(file), ByteArrayInputStream(bytes))
                }
            if (result.isEmpty()) {
                throw GoogleDriveAuthException(
                    "The Google Drive link contains no downloadable JPEG or WebP photos."
                )
            }
            if (metadata.size > result.size) {
                logger.info(
                    "Google Drive media selection: discovered={}, selected={}, TikTokLimit={}",
                    metadata.size,
                    result.size,
                    limit,
                )
            }
            return result
        } catch (error: Throwable) {
            result.forEach { photo -> runCatching { photo.content.close() } }
            throw error
        }
    }

    private suspend fun getFileMetadata(fileId: String, token: String): DriveFileMetadata {
        val response = httpClient.get("$FILES_URL/$fileId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            url {
                parameters.append("fields", FILE_FIELDS)
                parameters.append("supportsAllDrives", "true")
            }
        }
        if (!response.status.isSuccess()) throw driveApiError("read file metadata", response.bodyAsText())
        return json.decodeFromString(response.bodyAsText())
    }

    private suspend fun listFolderFiles(folderId: String, token: String): List<DriveFileMetadata> {
        val result = mutableListOf<DriveFileMetadata>()
        var pageToken: String? = null
        do {
            val response = httpClient.get(FILES_URL) {
                header(HttpHeaders.Authorization, "Bearer $token")
                url {
                    parameters.append("q", "'$folderId' in parents and trashed = false")
                    parameters.append("fields", "nextPageToken,files($FILE_FIELDS)")
                    parameters.append("supportsAllDrives", "true")
                    parameters.append("includeItemsFromAllDrives", "true")
                    parameters.append("orderBy", "name_natural")
                    parameters.append("pageSize", "1000")
                    pageToken?.let { parameters.append("pageToken", it) }
                }
            }
            if (!response.status.isSuccess()) throw driveApiError("list folder files", response.bodyAsText())
            val page = json.decodeFromString<DriveFileList>(response.bodyAsText())
            result += page.files
            pageToken = page.nextPageToken
        } while (pageToken != null && result.size < MAX_DISCOVERED_FILES)
        return result
    }

    private suspend fun downloadFile(file: DriveFileMetadata, token: String): ByteArray {
        val declaredSize = file.size?.toLongOrNull()
        require(declaredSize == null || declaredSize <= MAX_DOWNLOAD_BYTES) {
            "Google Drive photo '${file.name}' is larger than ${MAX_DOWNLOAD_BYTES / 1024 / 1024} MB."
        }
        val response = httpClient.get("$FILES_URL/${file.id}") {
            header(HttpHeaders.Authorization, "Bearer $token")
            url {
                parameters.append("alt", "media")
                parameters.append("supportsAllDrives", "true")
            }
        }
        if (!response.status.isSuccess()) throw driveApiError("download '${file.name}'", response.bodyAsText())
        return response.body<ByteArray>().also { bytes ->
            require(bytes.size <= MAX_DOWNLOAD_BYTES) {
                "Google Drive photo '${file.name}' is larger than ${MAX_DOWNLOAD_BYTES / 1024 / 1024} MB."
            }
        }
    }

    private fun safeFileName(file: DriveFileMetadata): String {
        val extension = when (file.mimeType) {
            "image/webp" -> "webp"
            else -> "jpg"
        }
        val base = file.name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        return "${base.ifBlank { file.id }}.$extension"
    }

    private fun driveApiError(action: String, body: String) = GoogleDriveAuthException(
        "Google Drive could not $action: ${body.take(MAX_ERROR_BODY_LENGTH)}"
    )

    private companion object {
        const val FILES_URL = "https://www.googleapis.com/drive/v3/files"
        const val FILE_FIELDS = "id,name,mimeType,size,capabilities(canDownload)"
        const val MAX_DISCOVERED_FILES = 5_000
        const val MAX_DOWNLOAD_BYTES = 20L * 1024 * 1024
        const val MAX_ERROR_BODY_LENGTH = 500
        val SUPPORTED_IMAGE_MIME_TYPES = setOf("image/jpeg", "image/webp")
    }
}
