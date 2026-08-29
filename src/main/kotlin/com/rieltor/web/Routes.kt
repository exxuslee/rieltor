package com.rieltor.web

import com.rieltor.infrastructure.google.GoogleDriveAuthService
import com.rieltor.infrastructure.media.LocalPublicMediaStorage
import com.rieltor.infrastructure.oauth.OAuthStateStore
import com.rieltor.infrastructure.tiktok.TikTokAuthService
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.nio.file.Path

fun Route.tikTokVerificationRoutes(docsDirectory: Path) {
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

fun Route.mediaRoutes(storage: LocalPublicMediaStorage) {
    get("/media/{fileName}") {
        val media = call.parameters["fileName"]?.let(storage::resolve)
        if (media == null) {
            call.respondText("Not found", status = HttpStatusCode.NotFound)
        } else {
            call.respondFile(media.toFile())
        }
    }
}

fun Route.tikTokAuthRoutes(auth: TikTokAuthService, states: OAuthStateStore) {
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

fun Route.googleDriveAuthRoutes(auth: GoogleDriveAuthService, states: OAuthStateStore) {
    get("/auth/google/login") {
        val state = states.issue()
        call.respondRedirect(auth.buildAuthorizeUrl(state))
    }

    get("/auth/google/callback") {
        val error = call.request.queryParameters["error"]
        if (error != null) {
            call.respondText(
                "Google Drive authorization was not completed: $error - " +
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
        auth.exchangeCodeForTokens(code)
        call.respondText(
            "Google Drive account connected successfully.\nYou can close this window.",
            contentType = ContentType.Text.Plain,
        )
    }
}
