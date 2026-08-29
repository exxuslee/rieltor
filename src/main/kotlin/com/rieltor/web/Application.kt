package com.rieltor.web

import com.rieltor.application.PhotoRepostService
import com.rieltor.infrastructure.config.ApplicationSettings
import com.rieltor.infrastructure.config.bootstrapSecrets
import com.rieltor.infrastructure.database.LegacyTokenMigration
import com.rieltor.infrastructure.database.SqliteDatabase
import com.rieltor.infrastructure.database.SqliteRepositories
import com.rieltor.infrastructure.google.GoogleDriveAuthException
import com.rieltor.infrastructure.google.GoogleDriveAuthService
import com.rieltor.infrastructure.google.GoogleDrivePhotoSource
import com.rieltor.infrastructure.media.LocalPublicMediaStorage
import com.rieltor.infrastructure.media.MediaCleanupJob
import com.rieltor.infrastructure.telegram.TelegramClientAdapter
import com.rieltor.infrastructure.tiktok.OAuthStateStore
import com.rieltor.infrastructure.tiktok.TikTokAuthException
import com.rieltor.infrastructure.tiktok.TikTokAuthService
import com.rieltor.infrastructure.tiktok.TikTokPhotoPublisher
import io.github.cdimascio.dotenv.Dotenv
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.autohead.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.nio.file.Path
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

fun main() {
    val dotenv = Dotenv.configure().ignoreIfMissing().load()
    val port = (System.getenv("PORT") ?: dotenv.get("PORT"))?.toIntOrNull() ?: 8383
    embeddedServer(ServerCIO, port = port, host = "0.0.0.0") { module(dotenv) }
        .start(wait = true)
}

fun Application.module(dotenv: Dotenv) {
    val json = Json { ignoreUnknownKeys = true }
    val databasePath = Path.of(System.getenv("APP_DB_PATH") ?: dotenv.get("APP_DB_PATH") ?: "rieltor.db")
    val repositories = SqliteRepositories(SqliteDatabase(databasePath))
    bootstrapSecrets(repositories, dotenv)
    LegacyTokenMigration.migrateIfNeeded(repositories)
    val settings = ApplicationSettings.load(repositories, dotenv)

    val httpClient = HttpClient(CIO) {
        install(ClientContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 45_000
            socketTimeoutMillis = 45_000
        }
    }
    val auth = TikTokAuthService(httpClient, settings, repositories, json)
    val tikTokStates = OAuthStateStore()
    val googleAuth = GoogleDriveAuthService(httpClient, settings, repositories, json)
    val googleStates = OAuthStateStore()
    val googleDrivePhotos = GoogleDrivePhotoSource(httpClient, googleAuth, json)
    val mediaStorage = LocalPublicMediaStorage(settings.mediaDirectory, settings.publicBaseUrl)
    val mediaCleanupJob = MediaCleanupJob(settings.mediaDirectory)
    val publisher = TikTokPhotoPublisher(httpClient, auth, json)
    val repostService = PhotoRepostService(
        allowedSources = settings.monitoredTelegramTopics,
        jobs = repositories,
        mediaStorage = mediaStorage,
        publisher = publisher,
        externalPhotoSource = googleDrivePhotos,
    )
    val telegram = TelegramClientAdapter(
        apiId = settings.telegramApiId,
        apiHash = settings.telegramApiHash,
        sessionDirectory = settings.telegramSessionDirectory,
        monitoredTopics = settings.monitoredTelegramTopics,
        repostService = repostService,
        externalPhotoSource = googleDrivePhotos,
    )

    installScannerProtection()
    install(CallLogging) { level = Level.INFO }
    install(AutoHeadResponse)
    install(ServerContentNegotiation) { json(json) }
    install(StatusPages) {
        exception<TikTokAuthException> { call, cause ->
            call.respondText(
                text = "TikTok operation failed: ${cause.message}",
                status = HttpStatusCode.BadGateway,
            )
        }
        exception<GoogleDriveAuthException> { call, cause ->
            call.respondText(
                text = "Google Drive operation failed: ${cause.message}",
                status = HttpStatusCode.BadGateway,
            )
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled error", cause)
            call.respondText("Internal server error", status = HttpStatusCode.InternalServerError)
        }
    }

    routing {
        get("/") { call.respondText("Rieltor Telegram → TikTok integration is running.") }
        get("/health") { call.respondText("ok") }
        tikTokVerificationRoutes(Path.of("docs"))
        mediaRoutes(mediaStorage)
        tikTokAuthRoutes(auth, tikTokStates)
        googleDriveAuthRoutes(googleAuth, googleStates)
    }

    telegram.start()
    mediaCleanupJob.start()

    monitor.subscribe(ApplicationStopping) {
        mediaCleanupJob.close()
        telegram.close()
        httpClient.close()
    }

}
