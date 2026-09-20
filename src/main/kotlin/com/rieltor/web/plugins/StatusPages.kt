package com.rieltor.web.plugins

import com.rieltor.infrastructure.google.GoogleDriveAuthException
import com.rieltor.infrastructure.threads.ThreadsAuthException
import com.rieltor.infrastructure.tiktok.TikTokAuthException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*

/** Integration failures answer with 502, everything unexpected with 500. */
fun Application.configureStatusPages() {
    install(StatusPages) {
        integrationFailure<TikTokAuthException>("TikTok")
        integrationFailure<GoogleDriveAuthException>("Google Drive")
        integrationFailure<ThreadsAuthException>("Threads")
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled error", cause)
            call.respondText("Internal server error", status = HttpStatusCode.InternalServerError)
        }
    }
}

private inline fun <reified T : Throwable> StatusPagesConfig.integrationFailure(integration: String) {
    exception<T> { call, cause ->
        call.respondText(
            text = "$integration operation failed: ${cause.message}",
            status = HttpStatusCode.BadGateway,
        )
    }
}
