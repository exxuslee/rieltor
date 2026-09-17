package com.rieltor.infrastructure.database.model

/** Read projection: catalog requests do not load source metadata or publication history. */
data class CatalogListingRow(
    val id: Long, val title: String, val description: String, val rawText: String, val location: String?,
    val address: String?, val typeOfRealty: String?, val price: Long?, val currency: String?,
    val areaM2: Double?, val landAreaSotka: Double?, val rooms: Int?, val floor: Int?, val totalFloors: Int?,
    val photos: String, val governmentPrograms: String, val tags: String,
    val primeParams: String, val secondaryParams: String, val createdAt: Long, val sourceCreatedAt: Long,
) {
    companion object {
        const val COLUMNS = "id, title, description, rawText, location, address, typeOfRealty, price, currency, " +
                "areaM2, landAreaSotka, rooms, floor, totalFloors, photos, governmentPrograms, tags, " +
                "primeParams, secondaryParams, createdAt, sourceCreatedAt"
    }
}
