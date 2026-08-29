package com.rieltor.web

import com.rieltor.application.orchestration.TelegramRepostCoordinator
import com.rieltor.di.applicationModules
import com.rieltor.di.googleOAuthState
import com.rieltor.di.tikTokOAuthState
import com.rieltor.infrastructure.config.serverPort
import com.rieltor.infrastructure.google.GoogleDriveAuthException
import com.rieltor.infrastructure.google.GoogleDriveAuthService
import com.rieltor.infrastructure.media.LocalPublicMediaStorage
import com.rieltor.infrastructure.media.MediaCleanupJob
import com.rieltor.infrastructure.oauth.OAuthStateStore
import com.rieltor.infrastructure.tiktok.TikTokAuthException
import com.rieltor.infrastructure.tiktok.TikTokAuthService
import io.github.cdimascio.dotenv.Dotenv
import io.ktor.client.HttpClient
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
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import org.slf4j.event.Level
import java.nio.file.Path
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

fun main() {
    val dotenv = Dotenv.configure().ignoreIfMissing().load()
    embeddedServer(ServerCIO, port = serverPort(dotenv), host = "0.0.0.0") { module(dotenv) }
        .start(wait = true)
}

fun Application.module(dotenv: Dotenv) {
    install(Koin) {
        slf4jLogger()
        modules(applicationModules(dotenv))
    }

    val json = get<Json>()
    val auth = get<TikTokAuthService>()
    val tikTokStates = get<OAuthStateStore>(tikTokOAuthState)
    val googleAuth = get<GoogleDriveAuthService>()
    val googleStates = get<OAuthStateStore>(googleOAuthState)
    val mediaStorage = get<LocalPublicMediaStorage>()
    val mediaCleanupJob = get<MediaCleanupJob>()
    val repostCoordinator = get<TelegramRepostCoordinator>()
    val httpClient = get<HttpClient>()

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

    repostCoordinator.start()
    mediaCleanupJob.start()

    monitor.subscribe(ApplicationStopping) {
        mediaCleanupJob.close()
        repostCoordinator.close()
        httpClient.close()
    }

}
