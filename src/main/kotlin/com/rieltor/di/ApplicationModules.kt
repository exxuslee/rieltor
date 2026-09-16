package com.rieltor.di

import com.rieltor.application.orchestration.CatalogIngestionService
import com.rieltor.application.orchestration.CatalogRepostService
import com.rieltor.application.port.TelegramBotReplySender
import com.rieltor.application.port.TelegramInboxSource
import com.rieltor.application.usecase.ReplyWithFormattedListingUseCase
import com.rieltor.domain.repository.*
import com.rieltor.domain.service.ListingCaptionFormatter
import com.rieltor.infrastructure.config.*
import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.repository.CatalogRepository
import com.rieltor.infrastructure.database.repository.TikTokPublishThrottleRepositoryImpl
import com.rieltor.infrastructure.google.GoogleDriveAuthService
import com.rieltor.infrastructure.google.GoogleDrivePhotoSource
import com.rieltor.infrastructure.media.LocalPublicMediaStorage
import com.rieltor.infrastructure.media.MediaCleanupJob
import com.rieltor.infrastructure.oauth.OAuthStateStore
import com.rieltor.infrastructure.telegram.TelegramBotApiReplySender
import com.rieltor.infrastructure.telegram.TelegramClientAdapter
import com.rieltor.infrastructure.telegram.TelegramListingBot
import com.rieltor.infrastructure.threads.ThreadsAuthService
import com.rieltor.infrastructure.threads.ThreadsPhotoPublisher
import com.rieltor.infrastructure.tiktok.TikTokAuthService
import com.rieltor.infrastructure.tiktok.TikTokPhotoPublisher
import com.rieltor.web.LandingLeadSender
import io.github.cdimascio.dotenv.Dotenv
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

val tikTokOAuthState = named("tiktok-oauth-state")
val googleOAuthState = named("google-oauth-state")
val threadsOAuthState = named("threads-oauth-state")

fun applicationModules(dotenv: Dotenv): List<Module> = listOf(
    configurationModule(dotenv),
    persistenceModule,
    networkModule,
    applicationModule,
    integrationModule,
)

private fun configurationModule(dotenv: Dotenv) = module {
    single { dotenv }
    single {
        val secrets = get<SecretRepository>()
        bootstrapSecrets(secrets, get())
        ApplicationSettings.load(secrets, get())
    }
}

private val persistenceModule = module {
    single {
        val app = get<ApplicationSettings>()
        val root = java.nio.file.Path.of(System.getenv("APP_PROJECT_ROOT") ?: ".").toAbsolutePath().normalize()
        JsonSettingsStore(
            root.resolve("settings.json"), LocalSettings(
                minIntervalMs = app.repostMinIntervalMinutes * 60_000,
                maxMessagesPer24Hours = app.repostMaxMessagesPer24Hours, threadsEnabled = app.threadsEnabled,
            )
        )
    }
    single { RoomDatabaseStore(databasePath(get()), get(), ownsSettings = false) }
    single { CatalogRepository(get()) }
    single { JsonCredentialStore(credentialsPath(get())) }
    single<SecretRepository> { get<JsonCredentialStore>() }
    single<TikTokTokenRepository> { JsonTikTokTokenRepository(get()) }
    single<TikTokPublishThrottleRepository> { TikTokPublishThrottleRepositoryImpl(get()) }
    single<GoogleDriveTokenRepository> { JsonGoogleDriveTokenRepository(get()) }
    single<ThreadsTokenRepository> { JsonThreadsTokenRepository(get()) }


}

private val networkModule = module {
    single { Json { ignoreUnknownKeys = true } }
    single {
        HttpClient(CIO) {
            install(ContentNegotiation) { json(get()) }
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 45_000
                socketTimeoutMillis = 45_000
            }
        }
    }
}

private val applicationModule = module {
    single { ListingCaptionFormatter() }
    single {
        ReplyWithFormattedListingUseCase(
            externalPhotoSource = get(),
            replySender = get(),
            captionFormatter = get(),
            maxPhotoCount = get<ApplicationSettings>().telegramListingBotMaxPhotoCount,
        )
    }
    single { CatalogIngestionService(get(), get(), get(), get(), get()) }
    single {
        val app = get<ApplicationSettings>()
        CatalogRepostService(get(), get(), buildList {
            add(get<TikTokPhotoPublisher>())
            if (app.threadsConfigured) add(get<ThreadsPhotoPublisher>())
        }, get())
    }
}
private val integrationModule = module {
    single { LandingLeadSender(get(), get()) }
    single { TikTokAuthService(get(), get(), get(), get()) }
    single(tikTokOAuthState) { OAuthStateStore() }
    single { ThreadsAuthService(get(), get(), get(), get()) }
    single(threadsOAuthState) { OAuthStateStore() }
    single { GoogleDriveAuthService(get(), get(), get(), get()) }
    single(googleOAuthState) { OAuthStateStore() }

    single { GoogleDrivePhotoSource(get(), get(), get()) }
    single<ExternalPhotoSource> { get<GoogleDrivePhotoSource>() }
    single { TelegramBotApiReplySender(get<ApplicationSettings>().telegramListingBotToken) }
    single<TelegramBotReplySender> { get<TelegramBotApiReplySender>() }
    single {
        TelegramListingBot(
            botToken = get<ApplicationSettings>().telegramListingBotToken,
            replyUseCase = get(),
        )
    }
    single {
        val settings = get<ApplicationSettings>()
        LocalPublicMediaStorage(settings.mediaDirectory, settings.publicBaseUrl)
    }
    single<PublicMediaStorage> { get<LocalPublicMediaStorage>() }
    single {
        MediaCleanupJob(get<ApplicationSettings>().mediaDirectory, catalogRepository = get())
    }

    single {
        TikTokPhotoPublisher(
            httpClient = get(),
            auth = get(),
            json = get(),
            tikTokMode = get<ApplicationSettings>().tikTokMode,
            maxPhotoCount = get<ApplicationSettings>().repostMaxPhotoCount,
            publishRepository = get(),
            globalCooldownMillis = get<ApplicationSettings>().tikTokDailyLimitCooldownHours * 3_600_000L,
        )
    }
    single { ThreadsPhotoPublisher(get(), get(), get()) }
    single<TelegramInboxSource> {
        val settings = get<ApplicationSettings>()
        TelegramClientAdapter(
            apiId = settings.telegramApiId,
            apiHash = settings.telegramApiHash,
            sessionDirectory = settings.telegramSessionDirectory,
            monitoredTopics = settings.monitoredTelegramTopics + get<JsonSettingsStore>().snapshot().topicTypeMapping.keys.map { key ->
                val parts = key.split(':'); com.rieltor.domain.model.TelegramMonitoredTopic(
                parts[0].toLong(),
                parts[1].toLong()
            )
            },
            repository = get(), settings = get(),
        )
    }
}
