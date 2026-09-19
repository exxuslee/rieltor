package com.rieltor.web

import com.rieltor.application.orchestration.CatalogIngestionService
import com.rieltor.application.orchestration.CatalogRepostService
import com.rieltor.di.applicationModules
import com.rieltor.di.googleOAuthState
import com.rieltor.di.threadsOAuthState
import com.rieltor.di.tikTokOAuthState
import com.rieltor.infrastructure.config.JsonSettingsStore
import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.repository.CatalogRepository
import com.rieltor.infrastructure.google.GoogleDriveAuthException
import com.rieltor.infrastructure.google.GoogleDriveAuthService
import com.rieltor.infrastructure.job.CleanupJob
import com.rieltor.infrastructure.media.LocalPublicMediaStorage
import com.rieltor.infrastructure.oauth.OAuthStateStore
import com.rieltor.infrastructure.telegram.TelegramListingBot
import com.rieltor.infrastructure.threads.ThreadsAuthException
import com.rieltor.infrastructure.threads.ThreadsAuthService
import com.rieltor.infrastructure.tiktok.TikTokAuthException
import com.rieltor.infrastructure.tiktok.TikTokAuthService
import io.ktor.client.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.autohead.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import org.slf4j.event.Level
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

fun main() {
    val root = Path.of(System.getenv("APP_PROJECT_ROOT") ?: ".").toAbsolutePath().normalize()
    val settings = JsonSettingsStore(root.resolve("settings.json"))
    embeddedServer(ServerCIO, port = settings.snapshot().serverPort, host = "0.0.0.0") { module(settings) }
        .start(wait = true)
}

fun Application.module(settings: JsonSettingsStore) {
    val delayedStartupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    install(Koin) {
        slf4jLogger()
        modules(applicationModules(settings))
    }

    val json = get<Json>()
    val auth = get<TikTokAuthService>()
    val tikTokStates = get<OAuthStateStore>(tikTokOAuthState)
    val googleAuth = get<GoogleDriveAuthService>()
    val googleStates = get<OAuthStateStore>(googleOAuthState)
    val threadsAuth = get<ThreadsAuthService>()
    val threadsStates = get<OAuthStateStore>(threadsOAuthState)
    val mediaStorage = get<LocalPublicMediaStorage>()
    val mediaCleanupJob = get<CleanupJob>()
    val catalog = get<CatalogRepository>()
    val ingestion = get<CatalogIngestionService>()
    val repostCoordinator = get<CatalogRepostService>()
    val telegramListingBot = get<TelegramListingBot>()
    val httpClient = get<HttpClient>()
    val landingLeadSender = get<LandingLeadSender>()

    installScannerProtection()
    install(CallLogging) { level = Level.INFO }
    install(AutoHeadResponse)
    install(CORS) {
        allowHost("rieltor.dpdns.org", schemes = listOf("https"))
        allowHost("localhost:4173", schemes = listOf("http"))
        allowMethod(HttpMethod.Post)
        allowHeader(HttpHeaders.ContentType)
    }
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
        exception<ThreadsAuthException> { call, cause ->
            call.respondText(
                text = "Threads operation failed: ${cause.message}",
                status = HttpStatusCode.BadGateway,
            )
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled error", cause)
            call.respondText("Internal server error", status = HttpStatusCode.InternalServerError)
        }
    }

    routing {
        get("/") { call.respondText("Rieltor Telegram → TikTok/Threads integration is running.") }
        get("/health") { call.respondText("ok") }
        tikTokVerificationRoutes(Path.of("docs"))
        mediaRoutes(mediaStorage)
        tikTokAuthRoutes(auth, tikTokStates)
        googleDriveAuthRoutes(googleAuth, googleStates)
        threadsAuthRoutes(threadsAuth, threadsStates)
        landingLeadRoutes(landingLeadSender)
        catalogRoutes(CatalogQuery(catalog, get<com.rieltor.infrastructure.config.ApplicationSettings>().publicBaseUrl))
    }

    ingestion.startTelegramSession()
    delayedStartupScope.launch {
        delay(STARTUP_AFTER_TELEGRAM_DELAY_MILLIS.milliseconds)
        catalog.recover(System.currentTimeMillis())
        ingestion.startWorkers()
        repostCoordinator.start()
        telegramListingBot.start()
        mediaCleanupJob.start()
    }


    monitor.subscribe(ApplicationStopping) {
        delayedStartupScope.cancel()
        mediaCleanupJob.close()
        ingestion.close()
        repostCoordinator.close()
        telegramListingBot.close()
        httpClient.close()
        get<RoomDatabaseStore>().close()
        get<JsonSettingsStore>().close()
    }

}

private const val STARTUP_AFTER_TELEGRAM_DELAY_MILLIS = 60_000L
