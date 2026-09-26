package com.rieltor.web.routing

import com.rieltor.application.service.StatisticsService
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json

/** Liveness and greeting endpoints. */
fun Route.systemRoutes(statistics: StatisticsService) {
    val statisticsJson = Json { encodeDefaults = true }
    get("/") { call.respondText("Rieltor Telegram → TikTok/Threads integration is running.") }
    get("/health") {
        val period = call.request.queryParameters["period"] ?: "day"
        val rawOffset = call.request.queryParameters["offset"]
        val offset = rawOffset?.toIntOrNull() ?: if (rawOffset == null) 0 else -1
        if (period !in setOf("day", "month", "total") || offset < 0) {
            call.respondText("Invalid period or offset", status = HttpStatusCode.BadRequest)
            return@get
        }
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondText(statisticsJson.encodeToString(statistics.snapshot(period, offset)), ContentType.Application.Json)
    }
    post("/api/visits/{visitorId}") {
        val visitorId = call.parameters["visitorId"].orEmpty()
        if (!Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(visitorId)) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }
        statistics.visit(visitorId)
        call.respond(HttpStatusCode.NoContent)
    }
}
