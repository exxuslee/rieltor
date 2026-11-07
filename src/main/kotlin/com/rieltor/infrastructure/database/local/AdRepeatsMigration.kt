package com.rieltor.infrastructure.database.local

import androidx.sqlite.SQLiteConnection

/** Recover only dated, accepted publications; processing times cannot date imported posts. */
internal fun restoreAdRepeats(connection: SQLiteConnection, stabilityMs: Long) {
    val recovered = mutableMapOf<Long, Int>()
    connection.prepare("""
        SELECT a.id, i.sourceCreatedAt
        FROM adsTab a
        JOIN statisticsEvents e ON e.adId=a.adId AND e.kind='ACCEPTED'
        JOIN incomeTab i ON i.chatId=e.chatId AND i.messageId=e.messageId
        WHERE i.sourceCreatedAt > 0
        GROUP BY a.id, e.chatId, e.messageId
        ORDER BY a.id, i.sourceCreatedAt, e.chatId, e.messageId
    """.trimIndent()).use { query ->
        var previousId: Long? = null
        var previousCreatedAt = 0L
        while (query.step()) {
            val id = query.getLong(0)
            val createdAt = query.getLong(1)
            if (id == previousId && createdAt - previousCreatedAt > stabilityMs) {
                recovered[id] = (recovered[id] ?: 0) + 1
            }
            // Follow the live counter's sliding stability window, including quick duplicate chains.
            previousId = id
            previousCreatedAt = createdAt
        }
    }
    connection.prepare("UPDATE adsTab SET repeats=MAX(repeats, ?) WHERE id=?").use { update ->
        recovered.forEach { (id, repeats) ->
            update.bindLong(1, repeats.toLong())
            update.bindLong(2, id)
            update.step()
            update.reset()
        }
    }
}
