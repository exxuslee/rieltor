package com.rieltor.infrastructure.config

import com.rieltor.domain.model.StoredGoogleDriveTokens
import com.rieltor.domain.model.StoredThreadsTokens
import com.rieltor.domain.model.StoredTokens
import com.rieltor.domain.repository.GoogleDriveTokenRepository
import com.rieltor.domain.repository.SecretRepository
import com.rieltor.domain.repository.ThreadsTokenRepository
import com.rieltor.domain.repository.TikTokTokenRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

class JsonCredentialStore(
    private val path: Path,
    private val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    },
) : SecretRepository {
    private val lock = Any()

    override fun get(name: String): String? = synchronized(lock) {
        read().secrets[name]
    }

    override fun putIfAbsent(name: String, value: String) {
        if (value.isBlank()) return
        synchronized(lock) {
            val current = read()
            if (name in current.secrets) return
            write(current.copy(secrets = current.secrets + (name to value)))
        }
    }

    internal fun saveTikTok(tokens: StoredTokens) = synchronized(lock) {
        val current = read()
        write(
            current.copy(
                tokens = current.tokens.copy(
                    tikTok = current.tokens.tikTok + (tokens.openId to tokens),
                    latestTikTokOpenId = tokens.openId,
                )
            )
        )
    }

    internal fun findTikTok(openId: String): StoredTokens? = synchronized(lock) {
        read().tokens.tikTok[openId]
    }

    internal fun latestTikTok(): StoredTokens? = synchronized(lock) {
        val tokens = read().tokens
        tokens.latestTikTokOpenId?.let(tokens.tikTok::get) ?: tokens.tikTok.values.firstOrNull()
    }

    internal fun saveGoogleDrive(tokens: StoredGoogleDriveTokens) = synchronized(lock) {
        val current = read()
        write(current.copy(tokens = current.tokens.copy(googleDrive = tokens)))
    }

    internal fun loadGoogleDrive(): StoredGoogleDriveTokens? = synchronized(lock) {
        read().tokens.googleDrive
    }

    internal fun saveThreads(tokens: StoredThreadsTokens) = synchronized(lock) {
        val current = read()
        write(current.copy(tokens = current.tokens.copy(threads = tokens)))
    }

    internal fun loadThreads(): StoredThreadsTokens? = synchronized(lock) {
        read().tokens.threads
    }

    private fun read(): CredentialFile {
        if (!Files.exists(path)) return CredentialFile()
        val content = Files.readString(path)
        if (content.isBlank()) return CredentialFile()
        return runCatching { json.decodeFromString<CredentialFile>(content) }
            .getOrElse { error("Cannot read credentials file '${path.toAbsolutePath()}': ${it.message}") }
    }

    private fun write(credentials: CredentialFile) {
        path.parent?.let(Files::createDirectories)
        val absolutePath = path.toAbsolutePath()
        val temporaryPath = absolutePath.resolveSibling("${absolutePath.fileName}.tmp")
        Files.writeString(temporaryPath, json.encodeToString(credentials) + System.lineSeparator())
        restrictFilePermissions(temporaryPath)
        try {
            Files.move(
                temporaryPath,
                absolutePath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporaryPath, absolutePath, StandardCopyOption.REPLACE_EXISTING)
        }
        restrictFilePermissions(absolutePath)
    }

    private fun restrictFilePermissions(file: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                file,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }
}

class JsonTikTokTokenRepository(private val store: JsonCredentialStore) : TikTokTokenRepository {
    override fun save(tokens: StoredTokens) = store.saveTikTok(tokens)
    override fun find(openId: String): StoredTokens? = store.findTikTok(openId)
    override fun latest(): StoredTokens? = store.latestTikTok()
}

class JsonGoogleDriveTokenRepository(private val store: JsonCredentialStore) : GoogleDriveTokenRepository {
    override fun save(tokens: StoredGoogleDriveTokens) = store.saveGoogleDrive(tokens)
    override fun load(): StoredGoogleDriveTokens? = store.loadGoogleDrive()
}

class JsonThreadsTokenRepository(private val store: JsonCredentialStore) : ThreadsTokenRepository {
    override fun save(tokens: StoredThreadsTokens) = store.saveThreads(tokens)
    override fun load(): StoredThreadsTokens? = store.loadThreads()
}

@Serializable
private data class CredentialFile(
    val schemaVersion: Int = 1,
    val secrets: Map<String, String> = emptyMap(),
    val tokens: CredentialTokens = CredentialTokens(),
)

@Serializable
private data class CredentialTokens(
    val tikTok: Map<String, StoredTokens> = emptyMap(),
    val latestTikTokOpenId: String? = null,
    val googleDrive: StoredGoogleDriveTokens? = null,
    val threads: StoredThreadsTokens? = null,
)
