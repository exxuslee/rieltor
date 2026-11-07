package com.rieltor.infrastructure.database.repository

import com.rieltor.domain.repository.TikTokRepository
import com.rieltor.infrastructure.database.local.RoomDatabaseStore

class TikTokRepositoryImpl(private val database: RoomDatabaseStore) : TikTokRepository {
    override fun blockUntil(blockedUntilMillis: Long) =
        database.settings.update { it.copy(blockedUntil = maxOf(it.blockedUntil, blockedUntilMillis)) }
}
