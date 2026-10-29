package com.rieltor.infrastructure.database

import com.rieltor.infrastructure.database.local.RoomDatabaseStore
import com.rieltor.infrastructure.database.repository.CatalogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatabaseDispatcherTest {
    @Test fun `database queries complete when all shared IO workers wait for Room`() {
        val database = RoomDatabaseStore(Files.createTempDirectory("room-io-starvation").resolve("test.db"))
        val repository = CatalogRepository(database)
        val ready = CountDownLatch(4)
        val start = CountDownLatch(1)
        val caller = Executors.newSingleThreadExecutor { task ->
            Thread(task, "database-starvation-test").apply { isDaemon = true }
        }
        val result = caller.submit<List<Int>> {
            runBlocking {
                List(4) { index ->
                    async(Dispatchers.IO) {
                        ready.countDown()
                        check(start.await(5, TimeUnit.SECONDS))
                        // Exercise both the synchronized transaction bridge and plain reads.
                        if (index % 2 == 0) repository.incoming().size else repository.listings().size
                    }
                }.awaitAll()
            }
        }
        var completed = false
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS), "Four IO callers must be ready before querying")
            start.countDown()
            assertEquals(listOf(0, 0, 0, 0), result.get(10, TimeUnit.SECONDS))
            completed = true
        } finally {
            start.countDown()
            caller.shutdownNow()
            // On regression, the daemon callers are deadlocked. Closing Room would also
            // block the test runner; the isolated test JVM releases these resources.
            if (completed) database.close()
        }
    }
}
