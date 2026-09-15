package com.rieltor.infrastructure.database.local

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal fun createCatalogTables(connection: SQLiteConnection) {
    connection.execSQL("CREATE TABLE IF NOT EXISTS incoming_telegram_messages (id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, chatId INTEGER NOT NULL, messageId INTEGER, messageThreadId INTEGER NOT NULL, mediaAlbumId INTEGER NOT NULL, groupKey TEXT NOT NULL, originalRawMessage TEXT NOT NULL, rawMessage TEXT NOT NULL, rawText TEXT NOT NULL, sourceCreatedAt INTEGER NOT NULL, sourceEditedAt INTEGER NOT NULL, receivedAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, contentHash TEXT NOT NULL, revision INTEGER NOT NULL, stableSince INTEGER NOT NULL, verifyAfter INTEGER NOT NULL, verifiedAt INTEGER, status TEXT NOT NULL, googleDriveUrls TEXT NOT NULL, mediaManifest TEXT NOT NULL, attemptCount INTEGER NOT NULL, nextAttemptAt INTEGER NOT NULL, lastError TEXT, leaseToken TEXT, leaseUntil INTEGER NOT NULL, listingId INTEGER, promotedAt INTEGER, deletedAt INTEGER, legacyUpdateId INTEGER)")
    connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_incoming_telegram_messages_chatId_messageId ON incoming_telegram_messages(chatId, messageId)")
    connection.execSQL("CREATE INDEX IF NOT EXISTS index_incoming_telegram_messages_groupKey ON incoming_telegram_messages(groupKey)")
    connection.execSQL("CREATE INDEX IF NOT EXISTS index_incoming_telegram_messages_status_verifyAfter ON incoming_telegram_messages(status, verifyAfter)")
    connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_incoming_telegram_messages_legacyUpdateId ON incoming_telegram_messages(legacyUpdateId)")
    connection.execSQL("CREATE TABLE IF NOT EXISTS listings (id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, groupKey TEXT NOT NULL, chatId INTEGER NOT NULL, messageId INTEGER, messageThreadId INTEGER NOT NULL, mediaAlbumId INTEGER NOT NULL, rawMessage TEXT NOT NULL, sourceRevision TEXT NOT NULL, title TEXT NOT NULL, description TEXT NOT NULL, location TEXT, address TEXT, district TEXT, typeOfRealty TEXT, transactionType TEXT NOT NULL, tags TEXT NOT NULL, primeParams TEXT NOT NULL, secondaryParams TEXT NOT NULL, governmentPrograms TEXT NOT NULL, governmentProgramsKnown INTEGER NOT NULL, googleDriveUrl TEXT, googleDriveUrls TEXT NOT NULL, price INTEGER, currency TEXT, pricePeriod TEXT NOT NULL, areaM2 REAL, landAreaSotka REAL, rooms INTEGER, floor INTEGER, totalFloors INTEGER, photos TEXT NOT NULL, coverPhotoId TEXT, status TEXT NOT NULL, sourceCreatedAt INTEGER NOT NULL, receivedAt INTEGER NOT NULL, cdt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, publishedAt INTEGER, tiktokReposted INTEGER NOT NULL, threadsReposted INTEGER NOT NULL, tiktokRepostedAt INTEGER, threadsRepostedAt INTEGER, tiktokStatus TEXT NOT NULL, threadsStatus TEXT NOT NULL, tiktokPublishId TEXT, threadsPublishId TEXT, tiktokState TEXT NOT NULL, threadsState TEXT NOT NULL, parserVersion INTEGER NOT NULL, parseWarnings TEXT NOT NULL, legacyUpdateId INTEGER, legacySnapshot TEXT)")
    connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_listings_groupKey ON listings(groupKey)")
    connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_listings_chatId_messageId ON listings(chatId, messageId)")
    connection.execSQL("CREATE INDEX IF NOT EXISTS index_listings_status_sourceCreatedAt_id ON listings(status, sourceCreatedAt, id)")
    connection.execSQL("CREATE INDEX IF NOT EXISTS index_listings_status_location_typeOfRealty_currency_price ON listings(status, location, typeOfRealty, currency, price)")
    connection.execSQL("CREATE INDEX IF NOT EXISTS index_listings_status_tiktokStatus_sourceCreatedAt ON listings(status, tiktokStatus, sourceCreatedAt)")
    connection.execSQL("CREATE INDEX IF NOT EXISTS index_listings_status_threadsStatus_sourceCreatedAt ON listings(status, threadsStatus, sourceCreatedAt)")
    connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_listings_legacyUpdateId ON listings(legacyUpdateId)")
}

