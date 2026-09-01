package com.rieltor.domain.service

import kotlin.test.Test
import kotlin.test.assertEquals

class GoogleDriveLinkExtractorTest {
    private val extractor = GoogleDriveLinkExtractor()

    @Test
    fun `extracts distinct drive links without trailing punctuation`() {
        val folder = "https://drive.google.com/drive/folders/folder-123"
        val file = "https://drive.google.com/file/d/file-456"

        assertEquals(
            listOf(folder, file),
            extractor.extract("Фото: $folder, дубль: $folder; файл: $file."),
        )
    }
}
