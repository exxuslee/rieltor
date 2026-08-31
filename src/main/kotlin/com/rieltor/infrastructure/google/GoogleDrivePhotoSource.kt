package com.rieltor.infrastructure.google

import com.rieltor.domain.model.TelegramPhoto
import com.rieltor.domain.repository.ExternalPhotoSource
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

sealed interface DriveTarget {
    val id: String

    data class File(override val id: String) : DriveTarget
    data class Folder(override val id: String) : DriveTarget
    data class Unknown(override val id: String) : DriveTarget
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
    private val unknownQueryPattern = Regex(
        """https?://(?:www\.)?drive\.google\.com/open\?(?:[^\s#&]+&)*id=($ID)""",
        RegexOption.IGNORE_CASE,
    )
    private val queryFilePattern = Regex(
        """https?://(?:www\.)?drive\.google\.com/uc\?(?:[^\s#&]+&)*id=($ID)""",
        RegexOption.IGNORE_CASE,
    )

    fun extract(text: String?): List<DriveTarget> {
        if (text.isNullOrBlank()) return emptyList()
        val matches = buildList {
            folderPattern.findAll(text).forEach { add(it.range.first to DriveTarget.Folder(it.groupValues[1])) }
            filePattern.findAll(text).forEach { add(it.range.first to DriveTarget.File(it.groupValues[1])) }
            unknownQueryPattern.findAll(text).forEach { add(it.range.first to DriveTarget.Unknown(it.groupValues[1])) }
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
    private val downloadSemaphore = Semaphore(MAX_CONCURRENT_DOWNLOAD_BATCHES)

    override fun containsLink(text: String?): Boolean = GoogleDriveLinkParser.extract(text).isNotEmpty()

    override suspend fun downloadPhotos(text: String?, limit: Int): List<TelegramPhoto> {
        if (limit <= 0) return emptyList()
        val targets = GoogleDriveLinkParser.extract(text)
        if (targets.isEmpty()) return emptyList()

        logger.info(
            "Google Drive photo batch queued. targets={}, limit={}, concurrentBatchLimit={}",
            targets.size,
            limit,
            MAX_CONCURRENT_DOWNLOAD_BATCHES,
        )
        return downloadSemaphore.withPermit {
            logger.info("Google Drive photo batch started. targets={}, limit={}", targets.size, limit)
            downloadPhotoBatch(targets, limit)
        }
    }

    private suspend fun downloadPhotoBatch(targets: List<DriveTarget>, limit: Int): List<TelegramPhoto> {
        val token = auth.validAccessToken()
        val metadata = linkedMapOf<String, DriveFileMetadata>()
        targets.forEach { target ->
            val targetFiles = when (target) {
                is DriveTarget.File -> listOf(getFileMetadata(target.id, token))
                is DriveTarget.Folder -> listFolderFiles(target.id, token)
                is DriveTarget.Unknown -> {
                    val targetMetadata = getFileMetadata(target.id, token)
                    if (targetMetadata.mimeType == GOOGLE_DRIVE_FOLDER_MIME_TYPE) {
                        listFolderFiles(target.id, token)
                    } else {
                        listOf(targetMetadata)
                    }
                }
            }
            targetFiles.forEach { file -> metadata.putIfAbsent(file.id, file) }
        }

        val candidates = metadata.values.asSequence()
            .filter { it.mimeType in SUPPORTED_IMAGE_MIME_TYPES }
            .filter { it.capabilities?.canDownload != false }
            .toList()
        val result = mutableListOf<TelegramPhoto>()
        var lastDownloadError: Throwable? = null
        var failedDownloadCount = 0
        try {
            for (file in candidates) {
                if (result.size >= limit) break
                try {
                    val bytes = downloadFile(file, token)
                    result += TelegramPhoto(safeFileName(file), ByteArrayInputStream(bytes))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    lastDownloadError = error
                    failedDownloadCount++
                    logger.warn(
                        "Skipping Google Drive photo after download retries. fileId={}, fileName={}, reason={}",
                        file.id,
                        file.name,
                        error.message ?: error.javaClass.simpleName,
                    )
                }
            }
            val requestedPhotoCount = minOf(candidates.size, limit)
            logger.info(
                "Google Drive photo batch completed. downloaded={}/{}, failed={}, available={}, limit={}",
                result.size,
                requestedPhotoCount,
                failedDownloadCount,
                candidates.size,
                limit,
            )
            if (result.isEmpty()) {
                val reason = lastDownloadError?.let { "; last failure: ${it.message ?: it.javaClass.simpleName}" }.orEmpty()
                throw GoogleDriveAuthException(
                    if (candidates.isEmpty()) {
                        "The Google Drive link contains no downloadable JPEG or WebP photos."
                    } else {
                        "Google Drive could not download any JPEG or WebP photos$reason"
                    }
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
        var lastError: Throwable? = null
        repeat(DOWNLOAD_MAX_ATTEMPTS) { attempt ->
            try {
                val response = httpClient.get("$FILES_URL/${file.id}") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    timeout {
                        requestTimeoutMillis = DOWNLOAD_TIMEOUT_MILLIS
                        socketTimeoutMillis = DOWNLOAD_TIMEOUT_MILLIS
                    }
                    url {
                        parameters.append("alt", "media")
                        parameters.append("supportsAllDrives", "true")
                    }
                }
                if (!response.status.isSuccess()) {
                    val error = driveApiError("download '${file.name}'", response.bodyAsText())
                    if (response.status.value < 500 || attempt + 1 >= DOWNLOAD_MAX_ATTEMPTS) throw error
                    lastError = error
                } else {
                    return response.body<ByteArray>().also { bytes ->
                        require(bytes.size <= MAX_DOWNLOAD_BYTES) {
                            "Google Drive photo '${file.name}' is larger than ${MAX_DOWNLOAD_BYTES / 1024 / 1024} MB."
                        }
                    }
                }
            } catch (error: IOException) {
                lastError = error
                if (attempt + 1 >= DOWNLOAD_MAX_ATTEMPTS) throw error
            }
            val retryDelay = DOWNLOAD_RETRY_DELAY_MILLIS * (attempt + 1)
            val failure = requireNotNull(lastError)
            logger.warn(
                "Temporary Google Drive photo download failure; retrying. fileId={}, attempt={}/{}, retryInSeconds={}, reason={}",
                file.id,
                attempt + 1,
                DOWNLOAD_MAX_ATTEMPTS,
                retryDelay / 1_000,
                failure.message ?: failure.javaClass.simpleName,
            )
            delay(retryDelay.milliseconds)
        }
        throw IOException("Google Drive photo download failed after retries", lastError)
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
        const val MAX_DISCOVERED_FILES = 1000
        const val MAX_DOWNLOAD_BYTES = 20L * 1024 * 1024
        const val MAX_ERROR_BODY_LENGTH = 500
        const val MAX_CONCURRENT_DOWNLOAD_BATCHES = 2
        const val DOWNLOAD_MAX_ATTEMPTS = 2
        const val DOWNLOAD_TIMEOUT_MILLIS = 120_000L
        const val DOWNLOAD_RETRY_DELAY_MILLIS = 2_000L
        const val GOOGLE_DRIVE_FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        val SUPPORTED_IMAGE_MIME_TYPES = setOf("image/jpeg", "image/webp")
    }
}
