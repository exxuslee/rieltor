package com.rieltor.web.routing

import com.rieltor.web.api.CatalogListingApi
import com.rieltor.web.dto.ApiError
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/** Public catalog API consumed by the landing site. */
fun Route.catalogRoutes(api: CatalogListingApi) {
    get("/api/listings") {
        try {
            call.respond(api.list(call.request.queryParameters))
        } catch (error: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ApiError(error.message ?: "Invalid filters"))
        }
    }

    get("/api/listings/{id}") {
        val listing = call.parameters["id"]?.toLongOrNull()?.let(api::one)
        if (listing == null) {
            call.respond(HttpStatusCode.NotFound, ApiError("Listing not found"))
        } else {
            call.respond(listing)
        }
    }
}
