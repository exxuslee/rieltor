package com.rieltor.web.mapper

import com.rieltor.application.model.CatalogListingPage
import com.rieltor.domain.model.CatalogCodes
import com.rieltor.domain.model.CatalogPhoto
import com.rieltor.infrastructure.database.model.CatalogListingRow
import com.rieltor.web.dto.ListingPage
import com.rieltor.web.dto.PublicListing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Converts storage rows into the public API representation. */
class PublicListingMapper(
    publicBaseUrl: String,
    private val json: Json = Json,
) {
    private val mediaBaseUrl = "${publicBaseUrl.trimEnd('/')}/media"

    fun page(page: CatalogListingPage): ListingPage = ListingPage(page.items.map(::listing), page.nextCursor)

    fun listing(row: CatalogListingRow): PublicListing {
        val photos = photoUrls(row)
        return PublicListing(
            row.id.toString(),
            row.title,
            row.description,
            row.rawText,
            location(row),
            row.location,
            row.typeOfRealty,
            CatalogCodes.category(row.typeOfRealty),
            requireNotNull(row.price).toString(),
            row.currency,
            PRICE_PERIOD,
            TRANSACTION_TYPE,
            row.areaM2,
            row.rooms,
            floor(row),
            row.landAreaSotka,
            photos.firstOrNull().orEmpty(),
            photos,
            json.decodeFromString(row.governmentPrograms),
            json.decodeFromString(row.tags),
            json.parseToJsonElement(row.primeParams).jsonObject,
            json.parseToJsonElement(row.secondaryParams).jsonObject,
            row.createdAt,
            row.sourceCreatedAt,
        )
    }

    private fun photoUrls(row: CatalogListingRow): List<String> = json.decodeFromString<List<CatalogPhoto>>(row.photos)
        .map { "$mediaBaseUrl/${it.fileName}" }

    private fun location(row: CatalogListingRow): String = listOfNotNull(
        CatalogCodes.locationName(row.location).takeIf { it.isNotEmpty() },
        row.address,
    ).joinToString(", ")

    private fun floor(row: CatalogListingRow): String? =
        row.floor?.let { floor -> "$floor${row.totalFloors?.let { total -> " із $total" }.orEmpty()}" }

    private companion object {
        const val PRICE_PERIOD = "TOTAL"
        const val TRANSACTION_TYPE = "SALE"
    }
}
