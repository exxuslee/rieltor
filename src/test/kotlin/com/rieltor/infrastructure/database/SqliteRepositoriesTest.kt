package com.rieltor.infrastructure.database

import com.rieltor.domain.model.StoredTokens
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SqliteRepositoriesTest {
    @Test
    fun `stores secrets tokens and idempotent jobs`() {
        val directory = Files.createTempDirectory("rieltor-db-test")
        val repository = SqliteRepositories(SqliteDatabase(directory.resolve("test.db")))

        repository.putIfAbsent("secret", "first")
        repository.putIfAbsent("secret", "second")
        assertEquals("first", repository.get("secret"))

        val tokens = StoredTokens("open", "access", "refresh", 100, 200)
        repository.save(tokens)
        assertEquals(tokens, repository.latest())

        assertTrue(repository.tryStart(7))
        assertFalse(repository.tryStart(7))
        repository.markFailed(7, "temporary")
        assertTrue(repository.tryStart(7))
        repository.markPublished(7, "publish-id")
        assertFalse(repository.tryStart(7))
    }
}
