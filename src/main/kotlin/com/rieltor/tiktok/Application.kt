package com.rieltor.tiktok

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

fun main() {
    val port = getRequiredEnv("PORT", dotenv).toIntOrNull() ?: 8383
    embeddedServer(CIO, port = port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    install(CallLogging) { level = Level.INFO }
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    install(StatusPages) {
        exception<TikTokAuthException> { call, cause ->
            call.respondText(
                text = "TikTok authorization failed: ${cause.message}",
                status = HttpStatusCode.BadGateway
            )
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled error", cause)
            call.respondText(
                text = "Internal error: ${cause.message}",
                status = HttpStatusCode.InternalServerError
            )
        }
    }

    routing {
        get("/") {
            call.respondText("Rieltor TikTok integration server is running.")
        }

        tikTokAuthRoutes()
    }
}

/**
 * Routes handling the TikTok Login Kit OAuth flow.
 *
 *  1. GET /auth/tiktok/login     -> redirects the browser to TikTok's consent screen
 *  2. GET /auth/tiktok/callback  -> TikTok redirects back here with ?code=&state=
 */
fun Route.tikTokAuthRoutes() {

    // Step 1: start the OAuth flow.
    // Open this URL in a browser (e.g. https://api.rieltor.dpdns.org/auth/tiktok/login)
    // to connect a TikTok account.
    get("/auth/tiktok/login") {
        val state = OAuthStateStore.issue()
        val authorizeUrl = TikTokAuthService.buildAuthorizeUrl(state)
        call.respondRedirect(authorizeUrl)
    }

    // Step 2: TikTok redirects here after the user approves (or denies) access.
    get("/auth/tiktok/callback") {
        val code = call.request.queryParameters["code"]
        val state = call.request.queryParameters["state"]
        val error = call.request.queryParameters["error"]
        val errorDescription = call.request.queryParameters["error_description"]

        if (error != null) {
            call.respondText(
                text = "TikTok authorization was not completed: $error - $errorDescription",
                status = HttpStatusCode.BadRequest
            )
            return@get
        }

        if (code == null || state == null) {
            call.respondText(
                text = "Missing 'code' or 'state' parameter.",
                status = HttpStatusCode.BadRequest
            )
            return@get
        }

        if (!OAuthStateStore.consume(state)) {
            call.respondText(
                text = "Invalid or expired state. Please restart the login flow at /auth/tiktok/login.",
                status = HttpStatusCode.Unauthorized
            )
            return@get
        }

        val tokens = TikTokAuthService.exchangeCodeForTokens(code)
        TokenStore.save(tokens)

        call.application.log.info("Connected TikTok account, openId=${tokens.openId}")

        call.respondText(
            text = """
                TikTok account connected successfully.
                openId: ${tokens.openId}
                You can close this window.
            """.trimIndent(),
            contentType = ContentType.Text.Plain
        )
    }
}
