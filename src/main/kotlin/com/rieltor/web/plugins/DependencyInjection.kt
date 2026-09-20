package com.rieltor.web.plugins

import com.rieltor.di.applicationModules
import com.rieltor.infrastructure.config.JsonSettingsStore
import io.ktor.server.application.*
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

fun Application.configureDependencyInjection(settings: JsonSettingsStore) {
    install(Koin) {
        slf4jLogger()
        modules(applicationModules(settings))
    }
}
