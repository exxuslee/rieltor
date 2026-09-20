package com.rieltor.web.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*

private val ALLOWED_ORIGINS = listOf(
    "rieltor.dpdns.org" to listOf("https"),
    "localhost:4173" to listOf("http"),
)

fun Application.configureCors() {
    install(CORS) {
        ALLOWED_ORIGINS.forEach { (host, schemes) -> allowHost(host, schemes = schemes) }
        allowMethod(HttpMethod.Post)
        allowHeader(HttpHeaders.ContentType)
    }
}
