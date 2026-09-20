package com.rieltor.application.service

import com.rieltor.application.worker.AdsWorker
import com.rieltor.application.worker.CatalogRepostWorker
import com.rieltor.application.worker.CleanupWorker
import com.rieltor.infrastructure.config.JsonSettingsStore
import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.repository.CatalogRepository
import com.rieltor.infrastructure.telegram.TelegramListingBot
import io.ktor.client.*
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Start and stop order of the background parts of the application.
 * Workers wait for the Telegram session so that the first poll already has an authorised client.
 */
class ApplicationLifecycle(
    private val catalog: CatalogRepository,
    private val adsWorker: AdsWorker,
    private val repostService: CatalogRepostWorker,
    private val telegramListingBot: TelegramListingBot,
    private val cleanupWorker: CleanupWorker,
    private val httpClient: HttpClient,
    private val database: RoomDatabaseStore,
    private val settingsStore: JsonSettingsStore,
    private val startupDelay: Duration = STARTUP_AFTER_TELEGRAM_DELAY,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun startTelegramSession() {
        adsWorker.startTelegramSession()
    }

    suspend fun startBackgroundWorkers() {
        delay(startupDelay)
        catalog.recover(now())
        adsWorker.startWorkers()
        repostService.start()
        telegramListingBot.start()
        cleanupWorker.start()
    }

    fun stop() {
        cleanupWorker.close()
        adsWorker.close()
        repostService.close()
        telegramListingBot.close()
        httpClient.close()
        database.close()
        settingsStore.close()
    }

    private companion object {
        val STARTUP_AFTER_TELEGRAM_DELAY: Duration = 1.minutes
    }
}
