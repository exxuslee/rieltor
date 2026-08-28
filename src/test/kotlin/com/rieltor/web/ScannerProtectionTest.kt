package com.rieltor.web

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScannerProtectionTest {
    @Test
    fun `blocks probes before routing but leaves normal routes available`() = testApplication {
        application {
            installScannerProtection()
            routing {
                get("/health") { call.respondText("ok") }
                get("/{path...}") { call.respondText("fallback") }
            }
        }

        assertEquals(HttpStatusCode.NotFound, client.get("/.env").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/%2eenv").status)
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
    }

    @Test
    fun `recognizes secret scanner paths and encoded variants`() {
        listOf(
            "/.env",
            "/backend/.env.local",
            "/%2eenv",
            "/%2f%2eenv",
            "/.git/config",
            "/.aws/credentials",
            "/wp-config.php.old",
            "/application.yml",
            "/private-key.pem",
        ).forEach { path -> assertTrue(ScannerProtection.isProbe(path), path) }
    }

    @Test
    fun `allows public application routes`() {
        listOf(
            "/",
            "/health",
            "/auth/tiktok/login",
            "/auth/tiktok/callback",
            "/media/apartment-photo.jpg",
            "/tiktok-site-verification.txt",
        ).forEach { path -> assertFalse(ScannerProtection.isProbe(path), path) }
    }
}
