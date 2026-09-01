package com.rieltor.infrastructure.config

import com.rieltor.domain.model.StoredGoogleDriveTokens
import com.rieltor.domain.model.StoredThreadsTokens
import com.rieltor.domain.model.StoredTokens
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonCredentialStoreTest {
    @Test
    fun `stores secrets and all oauth tokens in one json file`() {
        val path = Files.createTempDirectory("rieltor-credentials-test").resolve("secrets.json")
        val store = JsonCredentialStore(path)
        val tikTok = JsonTikTokTokenRepository(store)
        val google = JsonGoogleDriveTokenRepository(store)
        val threads = JsonThreadsTokenRepository(store)

        store.putIfAbsent("secret", "first")
        store.putIfAbsent("secret", "second")
        val tikTokTokens = StoredTokens("open", "access", "refresh", 100, 200)
        val googleTokens = StoredGoogleDriveTokens("google-access", "google-refresh", 300)
        val threadsTokens = StoredThreadsTokens("user", "threads-access", 400)
        tikTok.save(tikTokTokens)
        google.save(googleTokens)
        threads.save(threadsTokens)

        val reloaded = JsonCredentialStore(path)
        assertEquals("first", reloaded.get("secret"))
        assertEquals(tikTokTokens, JsonTikTokTokenRepository(reloaded).latest())
        assertEquals(tikTokTokens, JsonTikTokTokenRepository(reloaded).find("open"))
        assertEquals(googleTokens, JsonGoogleDriveTokenRepository(reloaded).load())
        assertEquals(threadsTokens, JsonThreadsTokenRepository(reloaded).load())
        assertTrue(Files.readString(path).contains("\"schemaVersion\": 1"))
    }
}
