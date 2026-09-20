package com.rieltor.web.routing

import com.rieltor.application.service.OAuthCallbackResult
import com.rieltor.application.service.OAuthLoginService
import com.rieltor.application.service.OAuthRegistry
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/** `/auth/{provider}/login` and `/auth/{provider}/callback` for every configured provider. */
fun Route.oauthRoutes(registry: OAuthRegistry) {
    registry.services.forEach { service -> oauthRoutes(service) }
}

fun Route.oauthRoutes(service: OAuthLoginService) {
    get("/auth/${service.providerId}/login") {
        call.respondRedirect(service.authorizeUrl())
    }

    get("/auth/${service.providerId}/callback") {
        val parameters = call.request.queryParameters
        val result = service.complete(
            code = parameters["code"],
            state = parameters["state"],
            error = parameters["error"],
            errorDescription = parameters["error_description"],
        )
        when (result) {
            is OAuthCallbackResult.Connected ->
                call.respondText(result.message, contentType = ContentType.Text.Plain)

            is OAuthCallbackResult.Declined ->
                call.respondText(result.message, status = HttpStatusCode.BadRequest)

            OAuthCallbackResult.InvalidState ->
                call.respondText("Invalid or expired OAuth callback.", status = HttpStatusCode.Unauthorized)
        }
    }
}
