package com.rieltor.di

import com.rieltor.application.orchestration.PersistentRepostMasterLimiter
import com.rieltor.application.orchestration.RepostMasterLimiter
import com.rieltor.application.orchestration.TelegramRepostCoordinator
import com.rieltor.application.port.PhotoRepostHandler
import com.rieltor.application.port.TelegramMessageSource
import com.rieltor.application.service.TelegramRepostTracker
import com.rieltor.application.usecase.PublishPhotoRepostUseCase
import com.rieltor.domain.repository.*
import com.rieltor.domain.service.ListingCaptionFormatter
import com.rieltor.infrastructure.config.*
import com.rieltor.infrastructure.database.TelegramHistoryCleanupJob
import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.repository.TelegramRepostQueueImpl
import com.rieltor.infrastructure.database.repository.TelegramRepostRepositoryImpl
import com.rieltor.infrastructure.database.repository.TikTokPublishThrottleRepositoryImpl
import com.rieltor.infrastructure.google.GoogleDriveAuthService
import com.rieltor.infrastructure.google.GoogleDrivePhotoSource
import com.rieltor.infrastructure.media.LocalPublicMediaStorage
import com.rieltor.infrastructure.media.MediaCleanupJob
import com.rieltor.infrastructure.oauth.OAuthStateStore
import com.rieltor.infrastructure.telegram.TelegramClientAdapter
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
    single { RoomDatabaseStore(databasePath(get())) }
    single { JsonCredentialStore(credentialsPath(get())) }
    single<SecretRepository> { get<JsonCredentialStore>() }
    single<TikTokTokenRepository> { JsonTikTokTokenRepository(get()) }
    single<TikTokPublishThrottleRepository> { TikTokPublishThrottleRepositoryImpl(get()) }
    single<GoogleDriveTokenRepository> { JsonGoogleDriveTokenRepository(get()) }
    single<ThreadsTokenRepository> { JsonThreadsTokenRepository(get()) }
    single<TelegramRepostRepository> { TelegramRepostRepositoryImpl(get()) }
    single<TelegramRepostQueue> { TelegramRepostQueueImpl(get()) }
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
    single { TelegramRepostTracker(get()) }
    single {
        val settings = get<ApplicationSettings>()
        PublishPhotoRepostUseCase(
            allowedSources = settings.monitoredTelegramTopics,
            repostTracker = get(),
            mediaStorage = get(),
            publishers = buildList {
                add(get<TikTokPhotoPublisher>())
                if (settings.threadsEnabled && settings.threadsConfigured) {
                    add(get<ThreadsPhotoPublisher>())
                }
            },
            externalPhotoSource = get(),
            captionFormatter = get(),
            maxPhotoCount = settings.repostMaxPhotoCount,
        )
    }
    single<PhotoRepostHandler> { get<PublishPhotoRepostUseCase>() }
    single<RepostMasterLimiter> {
        val settings = get<ApplicationSettings>()
        PersistentRepostMasterLimiter(
            repository = get(),
            maxMessagesPer24Hours = settings.repostMaxMessagesPer24Hours,
            minIntervalMillis = settings.repostMinIntervalMinutes * 60_000L,
        )
    }
    single { TelegramRepostCoordinator(get(), get(), get(), get()) }
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
    single {
        val settings = get<ApplicationSettings>()
        LocalPublicMediaStorage(settings.mediaDirectory, settings.publicBaseUrl)
    }
    single<PublicMediaStorage> { get<LocalPublicMediaStorage>() }
    single { MediaCleanupJob(get<ApplicationSettings>().mediaDirectory) }
    single { TelegramHistoryCleanupJob(get()) }
    single {
        TikTokPhotoPublisher(
            httpClient = get(),
            auth = get(),
            json = get(),
            tikTokMode = get<ApplicationSettings>().tikTokMode,
            maxPhotoCount = get<ApplicationSettings>().repostMaxPhotoCount,
        )
    }
    single { ThreadsPhotoPublisher(get(), get(), get()) }
    single<TelegramMessageSource> {
        val settings = get<ApplicationSettings>()
        TelegramClientAdapter(
            apiId = settings.telegramApiId,
            apiHash = settings.telegramApiHash,
            sessionDirectory = settings.telegramSessionDirectory,
            monitoredTopics = settings.monitoredTelegramTopics,
        )
    }
}
