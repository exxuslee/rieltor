package com.rieltor.di

import com.rieltor.application.orchestration.TelegramRepostCoordinator
import com.rieltor.application.port.PhotoRepostHandler
import com.rieltor.application.port.TelegramMessageSource
import com.rieltor.application.service.TelegramRepostTracker
import com.rieltor.application.usecase.PublishPhotoRepostUseCase
import com.rieltor.domain.repository.ExternalPhotoSource
import com.rieltor.domain.repository.GoogleDriveTokenRepository
import com.rieltor.domain.repository.PublicMediaStorage
import com.rieltor.domain.repository.SecretRepository
import com.rieltor.domain.repository.TelegramRepostRepository
import com.rieltor.domain.repository.TikTokTokenRepository
import com.rieltor.domain.repository.ThreadsTokenRepository
import com.rieltor.domain.service.TelegramListingIdentityExtractor
import com.rieltor.domain.service.ListingCaptionFormatter
import com.rieltor.infrastructure.config.ApplicationSettings
import com.rieltor.infrastructure.config.bootstrapSecrets
import com.rieltor.infrastructure.config.databasePath
import com.rieltor.infrastructure.database.LegacyTokenMigration
import com.rieltor.infrastructure.database.SqliteDatabase
import com.rieltor.infrastructure.database.GoogleDriveTokenRepositoryImpl
import com.rieltor.infrastructure.database.SecretRepositoryImpl
import com.rieltor.infrastructure.database.TelegramRepostRepositoryImpl
import com.rieltor.infrastructure.database.TikTokTokenRepositoryImpl
import com.rieltor.infrastructure.database.ThreadsTokenRepositoryImpl
import com.rieltor.infrastructure.google.GoogleDriveAuthService
import com.rieltor.infrastructure.google.GoogleDrivePhotoSource
import com.rieltor.infrastructure.media.LocalPublicMediaStorage
import com.rieltor.infrastructure.media.MediaCleanupJob
import com.rieltor.infrastructure.oauth.OAuthStateStore
import com.rieltor.infrastructure.telegram.TelegramClientAdapter
import com.rieltor.infrastructure.tiktok.TikTokAuthService
import com.rieltor.infrastructure.tiktok.TikTokPhotoPublisher
import com.rieltor.infrastructure.threads.ThreadsAuthService
import com.rieltor.infrastructure.threads.ThreadsPhotoPublisher
import io.github.cdimascio.dotenv.Dotenv
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
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
        LegacyTokenMigration.migrateIfNeeded(get())
        ApplicationSettings.load(secrets, get())
    }
}

private val persistenceModule = module {
    single { SqliteDatabase(databasePath(get())) }
    single<SecretRepository> { SecretRepositoryImpl(get()) }
    single<TikTokTokenRepository> { TikTokTokenRepositoryImpl(get()) }
    single<GoogleDriveTokenRepository> { GoogleDriveTokenRepositoryImpl(get()) }
    single<ThreadsTokenRepository> { ThreadsTokenRepositoryImpl(get()) }
    single<TelegramRepostRepository> { TelegramRepostRepositoryImpl(get()) }
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
    single { TelegramListingIdentityExtractor() }
    single { ListingCaptionFormatter() }
    single { TelegramRepostTracker(get(), get()) }
    single {
        val settings = get<ApplicationSettings>()
        PublishPhotoRepostUseCase(
            allowedSources = settings.monitoredTelegramTopics,
            repostTracker = get(),
            mediaStorage = get(),
            publishers = buildList {
                add(get<TikTokPhotoPublisher>())
                if (settings.threadsConfigured) add(get<ThreadsPhotoPublisher>())
            },
            externalPhotoSource = get(),
            captionFormatter = get(),
        )
    }
    single<PhotoRepostHandler> { get<PublishPhotoRepostUseCase>() }
    single { TelegramRepostCoordinator(get(), get()) }
}

private val integrationModule = module {
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
    single { TikTokPhotoPublisher(get(), get(), get()) }
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
