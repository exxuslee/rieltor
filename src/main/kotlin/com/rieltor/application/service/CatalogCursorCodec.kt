package com.rieltor.application.service

import com.rieltor.domain.model.CatalogCursor
import kotlinx.serialization.json.Json
import java.util.*

/** Encodes and decodes the opaque catalog pagination cursor. */
class CatalogCursorCodec(private val json: Json = Json) {

    fun encode(cursor: CatalogCursor): String = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(json.encodeToString(cursor).toByteArray())

    fun decode(encoded: String): CatalogCursor? = runCatching {
        json.decodeFromString<CatalogCursor>(Base64.getUrlDecoder().decode(encoded).decodeToString())
    }.getOrNull()

    companion object {
        const val MAX_ENCODED_LENGTH = 1024
    }
}
