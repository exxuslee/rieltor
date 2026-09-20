package com.rieltor.web

import com.rieltor.infrastructure.config.JsonSettingsStore
import com.rieltor.web.plugins.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import java.nio.file.Path
import io.ktor.server.cio.CIO as ServerCIO

fun main() {
    val root = Path.of(System.getenv("APP_PROJECT_ROOT") ?: ".").toAbsolutePath().normalize()
    val settings = JsonSettingsStore(root.resolve("settings.json"))
    embeddedServer(ServerCIO, port = settings.snapshot().serverPort, host = "0.0.0.0") { module(settings) }
        .start(wait = true)
}

fun Application.module(settings: JsonSettingsStore) {
    configureDependencyInjection(settings)
    installScannerProtection()
    configureMonitoring()
    configureCors()
    configureSerialization()
    configureStatusPages()
    configureRouting()
    configureLifecycle()
}
