package com.rieltor.infrastructure.database.repository

import com.rieltor.domain.model.RepostDestination
import com.rieltor.domain.model.TelegramListing
import com.rieltor.domain.model.TelegramMessageRegistration
import com.rieltor.domain.repository.TelegramRepostRepository
import com.rieltor.infrastructure.database.local.RegistrationResult
import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.model.TelegramListingRecord
import java.time.Instant

class TelegramRepostRepositoryImpl(private val database: RoomDatabaseStore) : TelegramRepostRepository {
    override fun register(listing: TelegramListing, destination: RepostDestination): TelegramMessageRegistration {
        val record = TelegramListingRecord.from(listing)
        return when (val result = database.blocking {
            it.repostDao().register(record, destination.name, Instant.now().epochSecond)
        }) {
            RegistrationResult.Accepted -> TelegramMessageRegistration.Accepted(record.repostKey)
            is RegistrationResult.Duplicate -> TelegramMessageRegistration.Duplicate(result.originalUpdateId)
        }
    }

    override fun markRepostPublished(
        telegramUpdateId: Long,
        destination: RepostDestination,
        publishId: String,
    ) = database.blocking {
        it.repostDao().markPublished(telegramUpdateId, destination.name, publishId, Instant.now().epochSecond)
    }

    override fun markRepostFailed(
        telegramUpdateId: Long,
        destination: RepostDestination,
        error: String,
    ) = database.blocking {
        it.repostDao().markFailed(telegramUpdateId, destination.name, error, Instant.now().epochSecond)
    }
}