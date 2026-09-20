package com.rieltor.domain.model

import kotlinx.serialization.Serializable

/** Sort orders supported by the public catalog API. */
enum class CatalogSort(val code: String, val column: String, val ascending: Boolean) {
    NEWEST("newest", "sourceCreatedAt", false),
    PRICE_ASC("priceAsc", "price", true),
    PRICE_DESC("priceDesc", "price", false),
    ;

    companion object {
        fun of(code: String): CatalogSort? = values().firstOrNull { it.code == code }
    }
}

/** Opaque keyset pagination pointer; the signature binds a cursor to the filters that produced it. */
@Serializable
data class CatalogCursor(val signature: String, val value: Long, val id: Long)

/** Validated catalog request: transport free, so the same filter is reusable outside HTTP. */
data class CatalogFilter(
    val locations: List<String> = emptyList(),
    val types: List<String> = emptyList(),
    val programs: List<String> = emptyList(),
    val priceMin: Long? = null,
    val priceMax: Long? = null,
    val rooms: Int? = null,
    val search: String? = null,
    val sort: CatalogSort = CatalogSort.NEWEST,
    val limit: Int = DEFAULT_LIMIT,
    val cursor: CatalogCursor? = null,
    val signature: String = "",
    val chatIds: List<Long> = emptyList(),
) {
    companion object {
        const val DEFAULT_LIMIT = 24
        const val MAX_LIMIT = 100
        const val MAX_SEARCH_LENGTH = 200
        val ROOMS_RANGE = 1..30
    }
}
