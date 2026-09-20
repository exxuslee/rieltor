package com.rieltor.web.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Listing as the public site sees it. Field names are part of the published API contract. */
@Serializable
data class PublicListing(
    val id: String, val title: String, val description: String, val rawText: String, val location: String,
    val locationCode: String?, val typeOfRealty: String?, val category: String,
    val price: String, val currency: String?, val pricePeriod: String, val transactionType: String,
    val area: Double?, val rooms: Int?, val floor: String?, val landAreaSotka: Double?,
    val image: String, val photos: List<String>, val governmentPrograms: List<String>,
    val tags: List<String>, val primeParams: JsonObject, val secondaryParams: JsonObject,
    // Keep the existing API key while the database uses the clearer createdAt name.
    val cdt: Long,
    val sourceCreatedAt: Long,
)

@Serializable
data class ListingPage(val items: List<PublicListing>, val nextCursor: String?)
