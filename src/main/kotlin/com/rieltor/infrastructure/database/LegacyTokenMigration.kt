package com.rieltor.infrastructure.database

import com.rieltor.domain.model.StoredTokens
import com.rieltor.domain.repository.TikTokTokenRepository
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

object LegacyTokenMigration {
    private val json = Json { ignoreUnknownKeys = true }

    fun migrateIfNeeded(repository: TikTokTokenRepository, legacyFile: Path = Path.of("tiktok-tokens.json")) {
        if (repository.latest() != null || !Files.isRegularFile(legacyFile)) return
        val tokens = json.decodeFromString<List<StoredTokens>>(Files.readString(legacyFile))
        tokens.forEach(repository::save)
    }
}
