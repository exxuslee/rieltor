package com.rieltor.di

import com.rieltor.application.port.LandingLeadNotifier
import com.rieltor.application.port.TelegramBotReplySender
import com.rieltor.application.port.TelegramInboxSource
import com.rieltor.application.service.*
import com.rieltor.application.worker.AdsWorker
import com.rieltor.application.worker.CatalogRepostWorker
import com.rieltor.application.worker.CleanupWorker
import com.rieltor.application.worker.ReplyTgBotWorker
import com.rieltor.domain.repository.*
import com.rieltor.domain.service.ListingCaptionFormatter
import com.rieltor.infrastructure.config.*
import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.repository.CatalogRepository
import com.rieltor.infrastructure.database.repository.TikTokRepositoryImpl
import com.rieltor.infrastructure.google.GoogleDriveAuthService
import com.rieltor.infrastructure.google.GoogleDrivePhotoSource
import com.rieltor.infrastructure.media.LocalPublicMediaStorage
import com.rieltor.infrastructure.media.VerificationFileStorage
import com.rieltor.infrastructure.oauth.OAuthStateStore
import com.rieltor.infrastructure.oauth.provider.GoogleDriveOAuthProvider
import com.rieltor.infrastructure.oauth.provider.ThreadsOAuthProvider
import com.rieltor.infrastructure.oauth.provider.TikTokOAuthProvider
import com.rieltor.infrastructure.telegram.TelegramBotApiReplySender
import com.rieltor.infrastructure.telegram.TelegramClientAdapter
import com.rieltor.infrastructure.telegram.TelegramLandingLeadNotifier
import com.rieltor.infrastructure.telegram.TelegramListingBot
import com.rieltor.infrastructure.threads.ThreadsAuthService
import com.rieltor.infrastructure.threads.ThreadsPhotoPublisher
import com.rieltor.infrastructure.tiktok.TikTokAuthService
import com.rieltor.infrastructure.tiktok.TikTokPhotoPublisher
import com.rieltor.web.api.CatalogListingApi
import com.rieltor.web.mapper.PublicListingMapper
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.nio.file.Path

val tikTokOAuthState = named("tiktok-oauth-state")
val googleOAuthState = named("google-oauth-state")
val threadsOAuthState = named("threads-oauth-state")

private val tikTokOAuthLogin = named("tiktok-oauth-login")
private val googleOAuthLogin = named("google-oauth-login")
private val threadsOAuthLogin = named("threads-oauth-login")

/** Directory with the platform ownership files served at the site root. */
private val verificationDirectory = Path.of("docs")

fun applicationModules(settingsStore: JsonSettingsStore): List<Module> = listOf(
    configurationModule(settingsStore),
    persistenceModule,
    networkModule,
    applicationModule,
    integrationModule,
    webModule,
)

private fun configurationModule(settingsStore: JsonSettingsStore) = module {
    single { settingsStore }
    single { ApplicationSettings.load(get(), get<JsonSettingsStore>().snapshot()) }
}

private val persistenceModule = module {
    single { RoomDatabaseStore(databasePath(get<JsonSettingsStore>().snapshot()), get(), ownsSettings = false) }
    single { CatalogRepository(get()) }
    single { JsonCredentialStore(credentialsPath(get<JsonSettingsStore>().snapshot())) }
    single<SecretRepository> { get<JsonCredentialStore>() }
    single<TikTokTokenRepository> { JsonTikTokTokenRepository(get()) }
    single<TikTokRepository> { TikTokRepositoryImpl(get()) }
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
        ReplyTgBotWorker(
            externalPhotoSource = get(),
            replySender = get(),
            captionFormatter = get(),
            maxPhotoCount = get<ApplicationSettings>().telegramListingBotMaxPhotoCount,
        )
    }
    single { AdsWorker(get(), get(), get(), get(), get()) }
    single { LandingLeadValidator() }
    single { LandingLeadRateLimiter() }
    single { LandingLeadService(get(), get(), get()) }
    single { CatalogCursorCodec() }
    single { CatalogFilterParser(get()) }
    single { CatalogQueryService(get(), get()) }
    single {
        val app = get<ApplicationSettings>()
        CatalogRepostWorker(get(), get(), buildList {
            add(get<TikTokPhotoPublisher>())
            if (app.threadsConfigured) add(get<ThreadsPhotoPublisher>())
        }, get())
    }
}
private val integrationModule = module {
    single<LandingLeadNotifier> { TelegramLandingLeadNotifier(get(), get()) }
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
        CleanupWorker(get<ApplicationSettings>().mediaDirectory, catalogRepository = get())
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

/** HTTP adapter: everything the routes need and nothing else. */
private val webModule = module {
    single { VerificationFileStorage(verificationDirectory) }
    single { PublicListingMapper(get<ApplicationSettings>().publicBaseUrl) }
    single { CatalogListingApi(get(), get(), get()) }

    single(tikTokOAuthLogin) { OAuthLoginService(TikTokOAuthProvider(get()), get(tikTokOAuthState)) }
    single(googleOAuthLogin) { OAuthLoginService(GoogleDriveOAuthProvider(get()), get(googleOAuthState)) }
    single(threadsOAuthLogin) { OAuthLoginService(ThreadsOAuthProvider(get()), get(threadsOAuthState)) }
    single {
        OAuthRegistry(
            listOf(
                get<OAuthLoginService>(tikTokOAuthLogin),
                get<OAuthLoginService>(googleOAuthLogin),
                get<OAuthLoginService>(threadsOAuthLogin),
            )
        )
    }

    single {
        ApplicationLifecycle(
            catalog = get(),
            adsWorker = get(),
            repostService = get(),
            telegramListingBot = get(),
            cleanupWorker = get(),
            httpClient = get(),
            database = get(),
            settingsStore = get(),
        )
    }
}
