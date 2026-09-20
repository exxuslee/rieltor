package com.rieltor.web.plugins

import com.rieltor.application.service.ApplicationLifecycle
import io.ktor.server.application.*
import kotlinx.coroutines.*
import org.koin.ktor.ext.get

/** Binds background workers to the Ktor application lifecycle. */
fun Application.configureLifecycle() {
    val lifecycle = get<ApplicationLifecycle>()
    val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lifecycle.startTelegramSession()
    startupScope.launch { lifecycle.startBackgroundWorkers() }

    monitor.subscribe(ApplicationStopping) {
        startupScope.cancel()
        lifecycle.stop()
    }
}
