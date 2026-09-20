package com.rieltor.web.routing

import com.rieltor.application.service.LandingLeadResult
import com.rieltor.application.service.LandingLeadService
import com.rieltor.web.dto.ApiAcknowledgement
import com.rieltor.web.dto.LandingLeadRequest
import io.ktor.http.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/** Contact forms of the landing site. */
fun Route.landingLeadRoutes(service: LandingLeadService) {
    post("/v1/landing/leads") {
        val clientId = call.request.headers[CLIENT_IP_HEADER] ?: call.request.origin.remoteHost
        val result = service.submit(clientId, call.receive<LandingLeadRequest>().toSubmission())
        call.respond(result.status(), ApiAcknowledgement(result == LandingLeadResult.ACCEPTED))
    }
}

private const val CLIENT_IP_HEADER = "X-Real-IP"

private fun LandingLeadResult.status(): HttpStatusCode = when (this) {
    LandingLeadResult.ACCEPTED -> HttpStatusCode.OK
    LandingLeadResult.RATE_LIMITED -> HttpStatusCode.TooManyRequests
    LandingLeadResult.INVALID -> HttpStatusCode.BadRequest
    LandingLeadResult.NOT_DELIVERED -> HttpStatusCode.ServiceUnavailable
}
