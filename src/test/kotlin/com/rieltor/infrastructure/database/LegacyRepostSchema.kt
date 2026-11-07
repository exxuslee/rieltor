package com.rieltor.infrastructure.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal fun restoreLegacyRepostTables(connection: SQLiteConnection) {
    connection.execSQL("ALTER TABLE adsTab DROP COLUMN repeats")
    for (table in listOf("repostTab", "repostAttemptsTab")) {
        connection.execSQL("""
            CREATE TABLE $table (
                id INTEGER NOT NULL PRIMARY KEY,
                tiktokRepostedAt INTEGER, threadsRepostedAt INTEGER,
                tiktokStatus TEXT NOT NULL, threadsStatus TEXT NOT NULL,
                tiktokState TEXT NOT NULL, threadsState TEXT NOT NULL,
                FOREIGN KEY(id) REFERENCES adsTab(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        connection.execSQL("CREATE INDEX index_" + table + "_tiktokStatus ON $table(tiktokStatus)")
        connection.execSQL("CREATE INDEX index_" + table + "_threadsStatus ON $table(threadsStatus)")
    }
    connection.execSQL("ALTER TABLE adsTab DROP COLUMN repostTikTok")
    connection.execSQL("ALTER TABLE adsTab DROP COLUMN repostThreads")
}
