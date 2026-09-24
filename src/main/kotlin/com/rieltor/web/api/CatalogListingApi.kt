package com.rieltor.web.api

import com.rieltor.application.service.CatalogFilterParser
import com.rieltor.application.service.CatalogQueryService
import com.rieltor.domain.model.CatalogCodes
import com.rieltor.domain.model.CatalogPhoto
import com.rieltor.infrastructure.database.model.CatalogListingRow
import com.rieltor.web.dto.ListingPage
import com.rieltor.web.dto.PublicListing
import io.ktor.http.*
import io.ktor.util.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Entry point of the catalog for HTTP: parses parameters, runs the query and maps the result.
 * Invalid filters surface as [IllegalArgumentException].
 */
class CatalogListingApi(
    publicBaseUrl: String,
    private val service: CatalogQueryService,
    private val parser: CatalogFilterParser = CatalogFilterParser(),
    private val json: Json = Json,
) {
    private val mediaBaseUrl = "${publicBaseUrl.trimEnd('/')}/media"

    fun list(parameters: Parameters): ListingPage {
        val page = service.page(parser.parse(parameters.toMap()))
        return ListingPage(page.items.map(::toPublic), page.nextCursor)
    }

    fun one(id: Long): PublicListing? = service.listing(id)?.let(::toPublic)

    private fun toPublic(row: CatalogListingRow): PublicListing {
        val photos = json.decodeFromString<List<CatalogPhoto>>(row.photos)
            .map { "$mediaBaseUrl/${it.fileName}" }

        return PublicListing(
            row.id.toString(),
            row.title,
            row.description,
            row.rawText,
            locationOf(row),
            row.location,
            row.typeOfRealty,
            CatalogCodes.category(row.typeOfRealty),
            requireNotNull(row.price).toString(),
            row.currency,
            PRICE_PERIOD,
            TRANSACTION_TYPE,
            row.areaM2,
            row.rooms,
            floorOf(row),
            row.landAreaSotka,
            photos.firstOrNull().orEmpty(),
            photos,
            json.decodeFromString(row.governmentPrograms),
            json.decodeFromString(row.tags),
            json.parseToJsonElement(row.primeParams).jsonObject,
            json.parseToJsonElement(row.secondaryParams).jsonObject,
            row.timestamp,
        )
    }

    private fun locationOf(row: CatalogListingRow): String = listOfNotNull(
        CatalogCodes.locationName(row.location).takeIf(String::isNotEmpty),
        row.address,
    ).joinToString(", ")

    private fun floorOf(row: CatalogListingRow): String? = row.floor?.let { floor ->
        row.totalFloors?.let { total -> "$floor із $total" } ?: floor.toString()
    }

    private companion object {
        const val PRICE_PERIOD = "TOTAL"
        const val TRANSACTION_TYPE = "SALE"
    }
}