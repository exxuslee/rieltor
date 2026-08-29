package com.rieltor.domain.repository

import com.rieltor.domain.model.StoredMedia
import java.io.InputStream

interface PublicMediaStorage {
    fun store(fileName: String, content: InputStream): StoredMedia
}
