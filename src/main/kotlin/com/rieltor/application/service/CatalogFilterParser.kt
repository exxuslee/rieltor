package com.rieltor.application.service

import com.rieltor.domain.model.*
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Turns raw query parameters into a validated [CatalogFilter].
 * Every rejection is reported as [IllegalArgumentException] so the transport layer can map it to 400.
 */
class CatalogFilterParser(private val cursors: CatalogCursorCodec = CatalogCursorCodec()) {

    fun parse(parameters: Map<String, List<String>>): CatalogFilter {
        val locations = codes(parameters, "location", CatalogCodes.locations)
        val types = codes(parameters, "typeOfRealty", CatalogCodes.types)
        val programs = codes(parameters, "governmentPrograms", CatalogCodes.programs)
        val priceMin = money(parameters, "priceMin")
        val priceMax = money(parameters, "priceMax")
        require(priceMin == null || priceMax == null || priceMin <= priceMax) { "priceMin must not exceed priceMax" }
        val sort = requireNotNull(CatalogSort.of(first(parameters, "sort") ?: CatalogSort.NEWEST.code)) { "Invalid sort" }
        val rooms = rooms(parameters)
        val search = search(parameters)
        val limit = limit(parameters)
        val signature = signature(parameters)

        return CatalogFilter(
            chatIds = parameters["chatId"].orEmpty().flatMap { it.split(',') }
                .filter { it.isNotEmpty() }
                .map { requireNotNull(it.toLongOrNull()) { "Invalid chatId" } }.distinct(),
            locations = locations,
            types = types,
            programs = programs,
            priceMin = priceMin,
            priceMax = priceMax,
            rooms = rooms,
            search = search,
            sort = sort,
            limit = limit,
            cursor = cursor(parameters, signature),
            signature = signature,
        )
    }

    private fun first(parameters: Map<String, List<String>>, name: String): String? =
        parameters[name]?.firstOrNull()

    private fun codes(parameters: Map<String, List<String>>, name: String, allowed: Set<String>): List<String> {
        val values = parameters[name].orEmpty()
            .flatMap { it.split(',') }
            .filter { it.isNotEmpty() }
            .distinct()
        require(values.all { it in allowed }) { "Invalid $name" }
        return values
    }

    private fun money(parameters: Map<String, List<String>>, name: String): Long? =
        first(parameters, name)?.takeIf { it.isNotEmpty() }?.let { raw ->
            val value = runCatching { BigDecimal(raw).setScale(0, RoundingMode.HALF_UP).longValueExact() }.getOrNull()
            require(value != null && value >= 0) { "Invalid $name" }
            value
        }

    private fun rooms(parameters: Map<String, List<String>>): Int? =
        first(parameters, "rooms")?.takeIf { it.isNotBlank() }?.let { raw ->
            val rooms = raw.toIntOrNull()
            require(rooms != null && rooms in CatalogFilter.ROOMS_RANGE) { "Invalid rooms" }
            rooms
        }

    private fun search(parameters: Map<String, List<String>>): String? =
        first(parameters, "query")?.trim()?.takeIf { it.isNotEmpty() }?.also {
            require(it.length <= CatalogFilter.MAX_SEARCH_LENGTH) { "Invalid query" }
        }

    private fun limit(parameters: Map<String, List<String>>): Int {
        val limit = first(parameters, "limit")
            ?.let { requireNotNull(it.toIntOrNull()) { "Invalid limit" } }
            ?: CatalogFilter.DEFAULT_LIMIT
        require(limit in 1..CatalogFilter.MAX_LIMIT) { "limit must be 1..${CatalogFilter.MAX_LIMIT}" }
        return limit
    }

    /** Fingerprint of the filters: a cursor stays valid only while the filters do not change. */
    private fun signature(parameters: Map<String, List<String>>): String = sha256(
        parameters.entries
            .filter { it.key !in CURSOR_INDEPENDENT }
            .sortedBy { it.key }
            .joinToString { "${it.key}=${it.value.sorted()}" }
    )

    private fun cursor(parameters: Map<String, List<String>>, signature: String): CatalogCursor? =
        first(parameters, "cursor")?.let { encoded ->
            require(encoded.length < CatalogCursorCodec.MAX_ENCODED_LENGTH) { "Invalid cursor" }
            val cursor = cursors.decode(encoded)
            require(cursor != null && cursor.signature == signature) { "Invalid cursor for current filters" }
            cursor
        }

    private companion object {
        val CURSOR_INDEPENDENT = setOf("cursor", "limit")
    }
}
