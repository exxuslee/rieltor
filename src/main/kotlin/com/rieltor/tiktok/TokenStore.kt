package com.rieltor.tiktok

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class StoredTokens(
    val openId: String,
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresAt: Long,   // epoch seconds
    val refreshTokenExpiresAt: Long,  // epoch seconds
)

/**
 * Minimal persistence for TikTok tokens.
 *
 * This is intentionally simple: an in-memory map backed by a single JSON file
 * on disk, so tokens survive a server restart during development.
 *
 * Replace this with a real table (e.g. Postgres/Exposed) before going to
 * production with multiple accounts or multiple server instances.
 */
object TokenStore {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file = File("tiktok-tokens.json")
    private val cache = ConcurrentHashMap<String, StoredTokens>()

    init {
        if (file.exists()) {
            runCatching {
                val list = json.decodeFromString<List<StoredTokens>>(file.readText())
                list.forEach { cache[it.openId] = it }
            }
        }
    }

    @Synchronized
    fun save(tokens: StoredTokens) {
        cache[tokens.openId] = tokens
        persist()
    }

    fun get(openId: String): StoredTokens? = cache[openId]

    fun all(): List<StoredTokens> = cache.values.toList()

    @Synchronized
    fun delete(openId: String) {
        cache.remove(openId)
        persist()
    }

    private fun persist() {
        file.writeText(json.encodeToString(cache.values.toList()))
    }

    fun isAccessTokenExpired(tokens: StoredTokens): Boolean =
        Instant.now().epochSecond >= tokens.accessTokenExpiresAt - 60 // refresh 60s early
}

/**
 * Short-lived storage for OAuth `state` values, used to prevent CSRF on the
 * callback endpoint. A state is valid once and expires after a few minutes.
 */
object OAuthStateStore {
    private data class Entry(val createdAt: Instant)

    private val states = ConcurrentHashMap<String, Entry>()
    private val ttlSeconds = 10 * 60L

    fun issue(): String {
        val state = java.util.UUID.randomUUID().toString()
        states[state] = Entry(Instant.now())
        return state
    }

    /** Returns true if the state was valid (and consumes it). */
    fun consume(state: String): Boolean {
        val entry = states.remove(state) ?: return false
        val age = Instant.now().epochSecond - entry.createdAt.epochSecond
        return age <= ttlSeconds
    }
}
