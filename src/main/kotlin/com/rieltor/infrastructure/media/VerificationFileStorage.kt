package com.rieltor.infrastructure.media

import java.nio.file.Files
import java.nio.file.Path

/** Serves the platform ownership files (e.g. `tiktokXXXX.txt`) from a single directory. */
class VerificationFileStorage(private val directory: Path) {

    fun resolve(fileName: String?): Path? = fileName
        ?.takeIf { it.matches(FILE_NAME) }
        ?.let(directory::resolve)
        ?.normalize()
        ?.takeIf { it.startsWith(directory.normalize()) && Files.isRegularFile(it) }

    private companion object {
        val FILE_NAME = Regex("tiktok[A-Za-z0-9]+\\.txt")
    }
}
