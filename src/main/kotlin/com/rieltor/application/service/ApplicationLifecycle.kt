package com.rieltor.application.service

import com.rieltor.infrastructure.config.JsonSettingsStore
import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.repository.CatalogRepository
import com.rieltor.infrastructure.job.CleanupJob
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
    private val ads: AdsService,
    private val repostService: CatalogRepostService,
    private val telegramListingBot: TelegramListingBot,
    private val mediaCleanupJob: CleanupJob,
    private val httpClient: HttpClient,
    private val database: RoomDatabaseStore,
    private val settingsStore: JsonSettingsStore,
    private val startupDelay: Duration = STARTUP_AFTER_TELEGRAM_DELAY,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun startTelegramSession() {
        ads.startTelegramSession()
    }

    suspend fun startBackgroundWorkers() {
        delay(startupDelay)
        catalog.recover(now())
        ads.startWorkers()
        repostService.start()
        telegramListingBot.start()
        mediaCleanupJob.start()
    }

    fun stop() {
        mediaCleanupJob.close()
        ads.close()
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
