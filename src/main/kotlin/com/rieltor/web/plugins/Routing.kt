package com.rieltor.web.plugins

import com.rieltor.application.service.LandingLeadService
import com.rieltor.application.service.OAuthRegistry
import com.rieltor.infrastructure.media.LocalPublicMediaStorage
import com.rieltor.infrastructure.media.VerificationFileStorage
import com.rieltor.web.api.CatalogListingApi
import com.rieltor.web.routing.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.get

/** Single place where every public endpoint of the service is registered. */
fun Application.configureRouting() {
    val verificationFiles = get<VerificationFileStorage>()
    val mediaStorage = get<LocalPublicMediaStorage>()
    val oauth = get<OAuthRegistry>()
    val landingLeads = get<LandingLeadService>()
    val catalog = get<CatalogListingApi>()

    routing {
        systemRoutes()
        verificationRoutes(verificationFiles)
        mediaRoutes(mediaStorage)
        oauthRoutes(oauth)
        landingLeadRoutes(landingLeads)
        catalogRoutes(catalog)
    }
}
