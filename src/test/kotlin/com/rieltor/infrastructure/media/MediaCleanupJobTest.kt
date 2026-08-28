package com.rieltor.infrastructure.media

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createDirectory
import kotlin.io.path.createFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaCleanupJobTest {
    @Test
    fun `deletes only supported images older than one day`() {
        val directory = Files.createTempDirectory("media-cleanup-test")
        val now = Instant.parse("2026-08-28T12:00:00Z")
        val oldImage = directory.resolve("old.jpg").createFile()
        val freshImage = directory.resolve("fresh.webp").createFile()
        val oldOtherFile = directory.resolve("old.txt").createFile()
        val nestedDirectory = directory.resolve("nested").createDirectory()
        val nestedOldImage = nestedDirectory.resolve("old.png").createFile()

        FileTime.from(now.minus(Duration.ofHours(25))).also { oldTime ->
            Files.setLastModifiedTime(oldImage, oldTime)
            Files.setLastModifiedTime(oldOtherFile, oldTime)
            Files.setLastModifiedTime(nestedOldImage, oldTime)
        }
        Files.setLastModifiedTime(freshImage, FileTime.from(now.minus(Duration.ofHours(23))))

        val cleanup = MediaCleanupJob(
            directory = directory,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        try {
            assertEquals(1, cleanup.cleanNow())
            assertFalse(Files.exists(oldImage))
            assertTrue(Files.exists(freshImage))
            assertTrue(Files.exists(oldOtherFile))
            assertTrue(Files.exists(nestedOldImage))
        } finally {
            cleanup.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `keeps image exactly one day old`() {
        val directory = Files.createTempDirectory("media-cleanup-boundary-test")
        val now = Instant.parse("2026-08-28T12:00:00Z")
        val image = directory.resolve("boundary.png").createFile()
        Files.setLastModifiedTime(image, FileTime.from(now.minus(Duration.ofDays(1))))
        val cleanup = MediaCleanupJob(
            directory = directory,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        try {
            assertEquals(0, cleanup.cleanNow())
            assertTrue(Files.exists(image))
        } finally {
            cleanup.close()
            directory.toFile().deleteRecursively()
        }
    }
}
