package com.rieltor.tools

import com.rieltor.application.worker.RepostWorker
import com.rieltor.infrastructure.config.*
import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.repository.CatalogRepository
import com.rieltor.infrastructure.database.repository.TikTokRepositoryImpl
import com.rieltor.infrastructure.media.LocalPublicMediaStorage
import com.rieltor.infrastructure.tiktok.TikTokAuthService
import com.rieltor.infrastructure.tiktok.TikTokPhotoPublisher
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Path

internal fun parseTikTokRepostId(args: Array<String>): Long {
    val id = args.singleOrNull()?.toLongOrNull()
    require(id != null && id > 0) {
        "Usage: ./gradlew repostTikTok --args=\"<positive adsTab.id>\""
    }
    return id
}

/** One-shot TikTok repost using public photo URLs served by nginx. */
fun main(args: Array<String>) = runBlocking {
    val id = parseTikTokRepostId(args)
    val root = Path.of(System.getenv("APP_PROJECT_ROOT") ?: ".").toAbsolutePath().normalize()
    JsonSettingsStore(root.resolve("settings.json")).use { settings ->
        val local = settings.snapshot()
        val secrets = JsonCredentialStore(credentialsPath(local))
        val app = ApplicationSettings.load(secrets, local)
        val json = Json { ignoreUnknownKeys = true }
        RoomDatabaseStore(databasePath(local), settings, ownsSettings = false).use { database ->
            val repository = CatalogRepository(database)
            checkNotNull(repository.listing(id)) { "adsTab.id=$id was not found" }
            HttpClient(CIO) {
                install(HttpTimeout) {
                    connectTimeoutMillis = 15_000
                    requestTimeoutMillis = 45_000
                    socketTimeoutMillis = 45_000
                }
            }.use { client ->
                val auth = TikTokAuthService(client, app, JsonTikTokTokenRepository(secrets), json)
                val publisher = TikTokPhotoPublisher(
                    client, auth, json, tikTokMode = app.tikTokMode,
                    maxPhotoCount = app.repostMaxPhotoCount,
                    publishRepository = TikTokRepositoryImpl(database),
                    globalCooldownMillis = app.tikTokDailyLimitCooldownHours * 3_600_000L,
                    enforceLocalPendingShareLimit = false,
                )
                val media = LocalPublicMediaStorage(app.mediaDirectory, app.publicBaseUrl)
                RepostWorker(repository, settings, listOf(publisher), media).use { worker ->
                    val result = worker.repostTikTok(id)
                    println("TikTok: adsTab.id=$id, status=${result.status.code}, publishId=${result.publishId}")
                }
            }
        }
    }
}
