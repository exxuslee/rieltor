package com.rieltor.web

import com.rieltor.application.PhotoRepostService
import com.rieltor.infrastructure.config.ApplicationSettings
import com.rieltor.infrastructure.config.bootstrapSecrets
import com.rieltor.infrastructure.database.LegacyTokenMigration
import com.rieltor.infrastructure.database.SqliteDatabase
import com.rieltor.infrastructure.database.SqliteRepositories
import com.rieltor.infrastructure.media.LocalPublicMediaStorage
import com.rieltor.infrastructure.telegram.TelegramBotAdapter
import com.rieltor.infrastructure.tiktok.OAuthStateStore
import com.rieltor.infrastructure.tiktok.TikTokAuthException
import com.rieltor.infrastructure.tiktok.TikTokAuthService
import com.rieltor.infrastructure.tiktok.TikTokPhotoPublisher
import io.github.cdimascio.dotenv.Dotenv
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.nio.file.Path

fun main() {
    val dotenv = loadDotenv()
    val port = (System.getenv("PORT") ?: dotenv.get("PORT"))?.toIntOrNull() ?: 8383
    embeddedServer(ServerCIO, port = port, host = "0.0.0.0") { module(dotenv) }
        .start(wait = true)
}

fun Application.module(dotenv: Dotenv = loadDotenv()) {
    val json = Json { ignoreUnknownKeys = true }
    val databasePath = Path.of(System.getenv("APP_DB_PATH") ?: dotenv.get("APP_DB_PATH") ?: "data/rieltor.db")
    val repositories = SqliteRepositories(SqliteDatabase(databasePath))
    bootstrapSecrets(repositories, dotenv)
    LegacyTokenMigration.migrateIfNeeded(repositories)
    val settings = ApplicationSettings.load(repositories, dotenv)

    val httpClient = HttpClient(CIO) {
        install(ClientContentNegotiation) { json(json) }
    }
    val auth = TikTokAuthService(httpClient, settings, repositories, json)
    val states = OAuthStateStore()
    val mediaStorage = LocalPublicMediaStorage(settings.mediaDirectory, settings.publicBaseUrl)
    val publisher = TikTokPhotoPublisher(httpClient, auth, json)
    val repostService = PhotoRepostService(
        allowedSenderId = settings.allowedTelegramSenderId,
        jobs = repositories,
        mediaStorage = mediaStorage,
        publisher = publisher,
    )
    val telegram = TelegramBotAdapter(
        botToken = settings.telegramBotToken,
        allowedSenderId = settings.allowedTelegramSenderId,
        repostService = repostService,
    )

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
        tikTokAuthRoutes(auth, states)
    }

    telegram.start()
    monitor.subscribe(ApplicationStopping) {
        telegram.close()
        httpClient.close()
    }
}

private fun Route.tikTokVerificationRoutes(docsDirectory: Path) {
    get("/{verificationFile}") {
        val fileName = call.parameters["verificationFile"]
        val file = fileName
            ?.takeIf { it.matches(Regex("tiktok[A-Za-z0-9]+\\.txt")) }
            ?.let(docsDirectory::resolve)
            ?.normalize()
            ?.takeIf { it.startsWith(docsDirectory.normalize()) && java.nio.file.Files.isRegularFile(it) }
        if (file == null) {
            call.respondText("Not found", status = HttpStatusCode.NotFound)
        } else {
            call.respondFile(file.toFile())
        }
    }
}

private fun Route.mediaRoutes(storage: LocalPublicMediaStorage) {
    get("/media/{fileName}") {
        val media = call.parameters["fileName"]?.let(storage::resolve)
        if (media == null) {
            call.respondText("Not found", status = HttpStatusCode.NotFound)
        } else {
            call.respondFile(media.toFile())
        }
    }
}

private fun Route.tikTokAuthRoutes(auth: TikTokAuthService, states: OAuthStateStore) {
    get("/auth/tiktok/login") {
        val state = states.issue()
        call.respondRedirect(auth.buildAuthorizeUrl(state))
    }

    get("/auth/tiktok/callback") {
        val error = call.request.queryParameters["error"]
        if (error != null) {
            call.respondText(
                "TikTok authorization was not completed: $error - " +
                    call.request.queryParameters["error_description"],
                status = HttpStatusCode.BadRequest,
            )
            return@get
        }
        val code = call.request.queryParameters["code"]
        val state = call.request.queryParameters["state"]
        if (code == null || state == null || !states.consume(state)) {
            call.respondText("Invalid or expired OAuth callback.", status = HttpStatusCode.Unauthorized)
            return@get
        }
        val tokens = auth.exchangeCodeForTokens(code)
        call.respondText(
            "TikTok account connected successfully.\nopenId: ${tokens.openId}\nYou can close this window.",
            contentType = ContentType.Text.Plain,
        )
    }
}

private fun loadDotenv(): Dotenv = Dotenv.configure().ignoreIfMissing().load()
