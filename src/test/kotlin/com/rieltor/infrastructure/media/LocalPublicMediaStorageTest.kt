package com.rieltor.infrastructure.media

import com.rieltor.domain.model.MediaTextOverlay
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalPublicMediaStorageTest {
    @Test
    fun `normalizes oversized photo to TikTok compatible jpeg`() {
        val directory = Files.createTempDirectory("media-storage-test")
        try {
            val storage = LocalPublicMediaStorage(directory, "https://api.example")
            val source = BufferedImage(2000, 1200, BufferedImage.TYPE_INT_ARGB)
            val graphics = source.createGraphics()
            try {
                graphics.color = Color(20, 40, 60, 120)
                graphics.fillRect(0, 0, source.width, source.height)
            } finally {
                graphics.dispose()
            }
            val input = ByteArrayOutputStream().also { ImageIO.write(source, "png", it) }.toByteArray()

            val stored = storage.store("oversized.png", ByteArrayInputStream(input))
            val output = ImageIO.read(Path.of(stored.localPath).toFile())

            assertEquals(1080, output.width)
            assertEquals(648, output.height)
            assertTrue(stored.publicUrl.endsWith(".jpg"))
            assertTrue(Files.size(Path.of(stored.localPath)) < 20L * 1024 * 1024)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `keeps smaller photo dimensions while converting to jpeg`() {
        val directory = Files.createTempDirectory("media-storage-small-test")
        try {
            val storage = LocalPublicMediaStorage(directory, "https://api.example")
            val source = BufferedImage(720, 960, BufferedImage.TYPE_INT_RGB)
            val input = ByteArrayOutputStream().also { ImageIO.write(source, "jpeg", it) }.toByteArray()

            val stored = storage.store("photo.jpeg", ByteArrayInputStream(input))
            val output = ImageIO.read(Path.of(stored.localPath).toFile())

            assertEquals(720, output.width)
            assertEquals(960, output.height)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `draws readable text cards in the lower left corner`() {
        val directory = Files.createTempDirectory("media-storage-overlay-test")
        try {
            val storage = LocalPublicMediaStorage(directory, "https://api.example")
            val source = BufferedImage(720, 960, BufferedImage.TYPE_INT_RGB).also { image ->
                image.createGraphics().run {
                    color = Color(30, 50, 70)
                    fillRect(0, 0, image.width, image.height)
                    dispose()
                }
            }
            val input = ByteArrayOutputStream().also { ImageIO.write(source, "jpeg", it) }.toByteArray()

            val stored = storage.store(
                "photo.jpeg",
                ByteArrayInputStream(input),
                MediaTextOverlay("Квартира в Ірпені", "Ціна 22 000 $", "066-372-71-02 Ірина"),
            )
            val output = ImageIO.read(Path.of(stored.localPath).toFile())
            val lightPixels = (0 until output.width / 2).sumOf { x ->
                (output.height / 2 until output.height).count { y ->
                    Color(output.getRGB(x, y)).run { red > 180 && green > 180 && blue > 180 }
                }
            }

            assertTrue(lightPixels > 5_000, "Expected translucent text cards in the lower-left quadrant")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
