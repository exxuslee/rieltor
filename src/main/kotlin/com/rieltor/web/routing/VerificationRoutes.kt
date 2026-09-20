package com.rieltor.web.routing

import com.rieltor.infrastructure.media.VerificationFileStorage
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/** Domain ownership files requested by TikTok at the site root. */
fun Route.verificationRoutes(storage: VerificationFileStorage) {
    get("/{verificationFile}") {
        val file = storage.resolve(call.parameters["verificationFile"])
        if (file == null) {
            call.respondText("Not found", status = HttpStatusCode.NotFound)
        } else {
            call.respondFile(file.toFile())
        }
    }
}
