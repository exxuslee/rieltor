package com.rieltor.web.routing

import io.ktor.server.response.*
import io.ktor.server.routing.*

/** Liveness and greeting endpoints. */
fun Route.systemRoutes() {
    get("/") { call.respondText("Rieltor Telegram → TikTok/Threads integration is running.") }
    get("/health") { call.respondText("ok") }
}
