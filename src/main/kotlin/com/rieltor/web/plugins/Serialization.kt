package com.rieltor.web.plugins

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.get
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

fun Application.configureSerialization() {
    val json = get<Json>()
    install(ServerContentNegotiation) { json(json) }
}
