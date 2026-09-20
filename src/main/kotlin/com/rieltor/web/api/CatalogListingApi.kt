package com.rieltor.web.api

import com.rieltor.application.service.CatalogFilterParser
import com.rieltor.application.service.CatalogQueryService
import com.rieltor.web.dto.ListingPage
import com.rieltor.web.dto.PublicListing
import com.rieltor.web.mapper.PublicListingMapper
import io.ktor.http.*
import io.ktor.util.*

/**
 * Entry point of the catalog for HTTP: parses parameters, runs the query and maps the result.
 * Invalid filters surface as [IllegalArgumentException].
 */
class CatalogListingApi(
    private val service: CatalogQueryService,
    private val mapper: PublicListingMapper,
    private val parser: CatalogFilterParser = CatalogFilterParser(),
) {
    fun list(parameters: Parameters): ListingPage = mapper.page(service.page(parser.parse(parameters.toMap())))

    fun one(id: Long): PublicListing? = service.listing(id)?.let(mapper::listing)
}
