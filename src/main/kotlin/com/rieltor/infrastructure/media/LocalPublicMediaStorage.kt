package com.rieltor.infrastructure.media

import com.rieltor.domain.model.StoredMedia
import com.rieltor.domain.repository.PublicMediaStorage
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID

class LocalPublicMediaStorage(
    private val directory: Path,
    private val publicBaseUrl: String,
) : PublicMediaStorage {
    init {
        Files.createDirectories(directory)
        setPublicDirectoryPermissions(directory)
    }

    override fun store(fileName: String, content: InputStream): StoredMedia {
        val extension = extensionOf(fileName)
        val publicName = "${UUID.randomUUID()}.$extension"
        val destination = directory.resolve(publicName).normalize()
        require(destination.parent == directory.toAbsolutePath().normalize() || destination.parent == directory.normalize()) {
            "Invalid media path"
        }
        Files.copy(content, destination, StandardCopyOption.REPLACE_EXISTING)
        setPublicFilePermissions(destination)
        return StoredMedia(
            publicUrl = "${publicBaseUrl.trimEnd('/')}/media/$publicName",
            localPath = destination.toString(),
        )
    }

    fun resolve(publicName: String): Path? {
        if (!publicName.matches(Regex("[0-9a-fA-F-]{36}\\.(jpg|jpeg|png|webp)"))) return null
        val candidate = directory.resolve(publicName).normalize()
        return candidate.takeIf { it.startsWith(directory.normalize()) && Files.isRegularFile(it) }
    }

    private fun extensionOf(fileName: String): String =
        fileName.substringAfterLast('.', "jpg").lowercase().let {
            if (it in setOf("jpg", "jpeg", "png", "webp")) it else "jpg"
        }

    private fun setPublicDirectoryPermissions(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE,
                ),
            )
        }
    }

    private fun setPublicFilePermissions(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.OTHERS_READ,
                ),
            )
        }
    }
}
