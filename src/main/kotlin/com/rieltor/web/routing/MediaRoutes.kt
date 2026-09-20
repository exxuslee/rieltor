package com.rieltor.web.routing

import com.rieltor.infrastructure.media.LocalPublicMediaStorage
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/** Public photos referenced by published listings. */
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
