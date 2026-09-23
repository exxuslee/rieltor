package com.rieltor.infrastructure.database.model

import androidx.room.TypeConverter
import com.rieltor.domain.model.ListingStatus

class ListingStatusConverters {
    @TypeConverter
    fun encode(status: ListingStatus): String = status.code

    @TypeConverter
    fun decode(code: String): ListingStatus = ListingStatus.fromCode(code)
}
