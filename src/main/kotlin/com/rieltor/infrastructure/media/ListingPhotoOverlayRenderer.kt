package com.rieltor.infrastructure.media

import com.rieltor.domain.model.MediaTextOverlay
import java.awt.*
import java.awt.image.BufferedImage

internal object ListingPhotoOverlayRenderer {
    fun draw(image: BufferedImage, overlay: MediaTextOverlay) {
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

            val shortestSide = minOf(image.width, image.height)
            val margin = (shortestSide * MARGIN_RATIO).toInt().coerceAtLeast(18)
            val horizontalPadding = (shortestSide * HORIZONTAL_PADDING_RATIO).toInt().coerceAtLeast(14)
            val verticalPadding = (shortestSide * VERTICAL_PADDING_RATIO).toInt().coerceAtLeast(8)
            val blockGap = (shortestSide * BLOCK_GAP_RATIO).toInt().coerceAtLeast(8)
            val maxTextWidth = (image.width - margin * 2 - horizontalPadding * 2).coerceAtLeast(1)
            val fontSize = (shortestSide * FONT_RATIO).toInt().coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
            val font = preferredFont(fontSize)
            graphics.font = font
            val metrics = graphics.fontMetrics

            val blocks = buildList {
                add(wrap(overlay.title, metrics, maxTextWidth))
                overlay.price?.takeIf(String::isNotBlank)?.let { add(wrap(it, metrics, maxTextWidth)) }
                add(wrap(overlay.contact, metrics, maxTextWidth))
            }
            var y = image.height - margin

            blocks.asReversed().forEach { lines ->
                val textWidth = lines.maxOf(metrics::stringWidth)
                val textHeight = lines.size * metrics.height
                val blockWidth = textWidth + horizontalPadding * 2
                val blockHeight = textHeight + verticalPadding * 2
                y -= blockHeight

                graphics.composite = AlphaComposite.SrcOver
                graphics.color = Color(248, 246, 241, BACKGROUND_ALPHA)
                val radius = (blockHeight * CORNER_RADIUS_RATIO).toInt().coerceAtLeast(12)
                graphics.fillRoundRect(margin, y, blockWidth, blockHeight, radius, radius)

                graphics.color = Color(18, 18, 18)
                lines.forEachIndexed { lineIndex, line ->
                    val baseline = y + verticalPadding + metrics.ascent + lineIndex * metrics.height
                    graphics.drawString(line, margin + horizontalPadding, baseline)
                }
                y -= blockGap
            }
        } finally {
            graphics.dispose()
        }
    }

    private fun wrap(text: String, metrics: FontMetrics, maxWidth: Int): List<String> {
        if (metrics.stringWidth(text) <= maxWidth) return listOf(text)

        val result = mutableListOf<String>()
        var current = StringBuilder()
        text.split(Regex("\\s+")).filter(String::isNotBlank).forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (current.isNotEmpty() && metrics.stringWidth(candidate) > maxWidth) {
                result += current.toString()
                current = StringBuilder(word)
            } else {
                current = StringBuilder(candidate)
            }
        }
        if (current.isNotEmpty()) result += current.toString()
        return result.ifEmpty { listOf(text) }
    }

    private fun preferredFont(size: Int): Font {
        // The logical family is a composite font: Java can use separate installed
        // glyph fonts for Ukrainian text and emoji within the same line.
        return Font(Font.SANS_SERIF, Font.PLAIN, size)
    }

    private const val MARGIN_RATIO = 0.05
    private const val HORIZONTAL_PADDING_RATIO = 0.025
    private const val VERTICAL_PADDING_RATIO = 0.012
    private const val BLOCK_GAP_RATIO = 0.014
    private const val FONT_RATIO = 0.047
    private const val CORNER_RADIUS_RATIO = 0.28
    private const val MIN_FONT_SIZE = 22
    private const val MAX_FONT_SIZE = 52
    private const val BACKGROUND_ALPHA = 218
}
