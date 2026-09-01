package com.rieltor.infrastructure.media

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import com.rieltor.domain.model.MediaTextOverlay
import com.rieltor.domain.model.StoredMedia
import com.rieltor.domain.repository.PublicMediaStorage
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.*
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

class LocalPublicMediaStorage(
    private val directory: Path,
    private val publicBaseUrl: String,
) : PublicMediaStorage {
    init {
        Files.createDirectories(directory)
        setPublicDirectoryPermissions(directory)
    }

    override fun store(fileName: String, content: InputStream, textOverlay: MediaTextOverlay?): StoredMedia {
        val bytes = content.readBytes()
        val image = ImageIO.read(ByteArrayInputStream(bytes))
            ?: throw IllegalArgumentException("Unsupported or corrupt image: $fileName")
        val oriented = ImageOrientationNormalizer.apply(image, readExifOrientation(bytes))
        val normalized = normalizeForPublish(oriented)
        textOverlay?.let { ListingPhotoOverlayRenderer.draw(normalized, it) }
        val publicName = "${UUID.randomUUID()}.jpg"
        val destination = directory.resolve(publicName).normalize()
        require(destination.parent == directory.toAbsolutePath().normalize() || destination.parent == directory.normalize()) {
            "Invalid media path"
        }
        writeJpeg(normalized, destination)
        setPublicFilePermissions(destination)
        return StoredMedia(
            publicUrl = "${publicBaseUrl.trimEnd('/')}/media/$publicName",
            localPath = destination.toString(),
        )
    }

    private fun readExifOrientation(bytes: ByteArray): Int = runCatching {
        ImageMetadataReader.readMetadata(ByteArrayInputStream(bytes))
            .getFirstDirectoryOfType(ExifIFD0Directory::class.java)
            ?.getInteger(ExifIFD0Directory.TAG_ORIENTATION)
            ?: EXIF_NORMAL_ORIENTATION
    }.getOrDefault(EXIF_NORMAL_ORIENTATION)

    fun resolve(publicName: String): Path? {
        if (!publicName.matches(Regex("[0-9a-fA-F-]{36}\\.(jpg|jpeg|png|webp)"))) return null
        val candidate = directory.resolve(publicName).normalize()
        return candidate.takeIf { it.startsWith(directory.normalize()) && Files.isRegularFile(it) }
    }

    private fun normalizeForPublish(source: BufferedImage): BufferedImage {
        val scale = minOf(
            1.0,
            TIKTOK_MAX_IMAGE_DIMENSION.toDouble() / source.width,
            TIKTOK_MAX_IMAGE_DIMENSION.toDouble() / source.height,
        )
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        return BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { target ->
            val graphics = target.createGraphics()
            try {
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, width, height)
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                graphics.drawImage(source, 0, 0, width, height, null)
            } finally {
                graphics.dispose()
            }
        }
    }

    private fun writeJpeg(image: BufferedImage, destination: Path) {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").asSequence().firstOrNull()
            ?: error("JPEG ImageIO writer is unavailable")
        try {
            Files.newOutputStream(destination).use { output ->
                ImageIO.createImageOutputStream(output).use { imageOutput ->
                    writer.output = imageOutput
                    val parameters = writer.defaultWriteParam.apply {
                        compressionMode = ImageWriteParam.MODE_EXPLICIT
                        compressionQuality = JPEG_COMPRESSION_QUALITY
                    }
                    writer.write(null, IIOImage(image, null, null), parameters)
                }
            }
        } finally {
            writer.dispose()
        }
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

    private companion object {
        // TikTok Photo API accepts JPEG/WebP images up to 1080p and 20 MB.
        const val TIKTOK_MAX_IMAGE_DIMENSION = 1080
        const val JPEG_COMPRESSION_QUALITY = 0.9f
        const val EXIF_NORMAL_ORIENTATION = 1
    }
}

internal object ImageOrientationNormalizer {
    fun apply(source: BufferedImage, orientation: Int): BufferedImage {
        if (orientation !in 2..8) return source
        val swapsDimensions = orientation in 5..8
        val target = BufferedImage(
            if (swapsDimensions) source.height else source.width,
            if (swapsDimensions) source.width else source.height,
            BufferedImage.TYPE_INT_ARGB,
        )
        val graphics = target.createGraphics()
        try {
            when (orientation) {
                2 -> {
                    graphics.translate(source.width, 0)
                    graphics.scale(-1.0, 1.0)
                }
                3 -> {
                    graphics.translate(source.width, source.height)
                    graphics.rotate(Math.PI)
                }
                4 -> {
                    graphics.translate(0, source.height)
                    graphics.scale(1.0, -1.0)
                }
                5 -> graphics.transform(java.awt.geom.AffineTransform(0.0, 1.0, 1.0, 0.0, 0.0, 0.0))
                6 -> graphics.transform(
                    java.awt.geom.AffineTransform(0.0, 1.0, -1.0, 0.0, source.height.toDouble(), 0.0)
                )
                7 -> graphics.transform(
                    java.awt.geom.AffineTransform(
                        0.0,
                        -1.0,
                        -1.0,
                        0.0,
                        source.height.toDouble(),
                        source.width.toDouble(),
                    )
                )
                8 -> graphics.transform(
                    java.awt.geom.AffineTransform(0.0, -1.0, 1.0, 0.0, 0.0, source.width.toDouble())
                )
            }
            graphics.drawImage(source, 0, 0, null)
        } finally {
            graphics.dispose()
        }
        return target
    }
}
