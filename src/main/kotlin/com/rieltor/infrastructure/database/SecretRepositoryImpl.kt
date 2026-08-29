package com.rieltor.infrastructure.database

import com.rieltor.domain.repository.SecretRepository
import java.time.Instant

class SecretRepositoryImpl(
    private val database: SqliteDatabase,
) : SecretRepository {
    override fun get(name: String): String? = database.connection().use { connection ->
        connection.prepareStatement("SELECT value FROM app_secrets WHERE name = ?").use { statement ->
            statement.setString(1, name)
            statement.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
        }
    }

    override fun putIfAbsent(name: String, value: String) {
        if (value.isBlank()) return
        database.connection().use { connection ->
            connection.prepareStatement(
                "INSERT OR IGNORE INTO app_secrets(name, value, updated_at) VALUES (?, ?, ?)"
            ).use { statement ->
                statement.setString(1, name)
                statement.setString(2, value)
                statement.setLong(3, Instant.now().epochSecond)
                statement.executeUpdate()
            }
        }
    }
}
