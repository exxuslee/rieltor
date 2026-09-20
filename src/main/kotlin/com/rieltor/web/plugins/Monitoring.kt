package com.rieltor.web.plugins

import io.ktor.server.application.*
import io.ktor.server.plugins.autohead.*
import io.ktor.server.plugins.calllogging.*
import org.slf4j.event.Level

fun Application.configureMonitoring() {
    install(CallLogging) { level = Level.INFO }
    install(AutoHeadResponse)
}
