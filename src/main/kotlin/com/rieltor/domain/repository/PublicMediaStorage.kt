package com.rieltor.domain.repository

import com.rieltor.domain.model.MediaTextOverlay
import com.rieltor.domain.model.StoredMedia
import java.io.InputStream

interface PublicMediaStorage {
    fun store(fileName: String, content: InputStream, textOverlay: MediaTextOverlay? = null): StoredMedia
}
